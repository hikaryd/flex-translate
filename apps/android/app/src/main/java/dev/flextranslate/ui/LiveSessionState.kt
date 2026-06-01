package dev.flextranslate.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.flextranslate.audio.AudioPipeline
import dev.flextranslate.audio.EnergyVad
import dev.flextranslate.audio.VadEvent
import dev.flextranslate.audio.VadState
import dev.flextranslate.foundation.AsrModelSpec
import dev.flextranslate.foundation.AsrModelSpecs
import dev.flextranslate.foundation.AsrModelStore
import dev.flextranslate.foundation.AsrProvider
import dev.flextranslate.foundation.AudioCaptureController
import dev.flextranslate.foundation.AudioCaptureController.CaptureStats
import dev.flextranslate.foundation.AudioFrame
import dev.flextranslate.foundation.CloudCallGate
import dev.flextranslate.foundation.CloudMediationClient
import dev.flextranslate.foundation.CloudOptInState
import dev.flextranslate.foundation.GeminiFlashConfig
import dev.flextranslate.foundation.GeminiFlashTranslationProvider
import dev.flextranslate.foundation.HttpCloudMediationClient
import dev.flextranslate.foundation.M2m100MtProvider
import dev.flextranslate.foundation.MilmmtMtProvider
import dev.flextranslate.foundation.MtCandidate
import dev.flextranslate.foundation.MtCandidateRegistry
import dev.flextranslate.foundation.MtExecution
import dev.flextranslate.foundation.MtModelSpec
import dev.flextranslate.foundation.MtModelSpecs
import dev.flextranslate.foundation.MtModelStore
import dev.flextranslate.foundation.OfflineFirstState
import dev.flextranslate.foundation.PlaceholderLocalAsrProvider
import dev.flextranslate.foundation.SherpaOnnxAsrProvider
import dev.flextranslate.foundation.TelemetryContext
import dev.flextranslate.foundation.TelemetrySink
import dev.flextranslate.foundation.TranscriptEvent
import dev.flextranslate.foundation.TranslationProvider
import dev.flextranslate.foundation.TranslationResult
import dev.flextranslate.foundation.WavPcmReader
import dev.flextranslate.foundation.emitWith
import dev.flextranslate.ui.i18n.Strings
import dev.flextranslate.ui.i18n.StringsRu
import java.io.File
import java.util.UUID

/** Phase-0 language scope — RU/EN/ZH. */
enum class FlexLanguage(val code: String, val label: String) {
    RU("ru", "Русский"),
    EN("en", "English"),
    ZH("zh", "中文"),
}

/**
 * Lightweight UI session holder. Owns the real [AudioCaptureController] and, when an offline
 * model is installed for the selected source language, the real [SherpaOnnxAsrProvider].
 *
 * G004/WS3 (A2): transcript is GENUINE recognizer output — partials while speaking, finals on
 * endpoint. When no model is installed the provider is the gated [PlaceholderLocalAsrProvider]
 * (returns `[]`) and the readiness reflects [OfflineFirstState.MissingOfflinePack] — never a
 * fabricated transcript.
 *
 * Not a ViewModel: created via remember in MainActivity so it survives recomposition; the host
 * Activity wires permission results and lifecycle stop.
 *
 * [uiStrings] is updated by the composition root whenever the user switches the interface language
 * so that translation-reason strings (produced outside the Compose tree) are localised.
 */
class LiveSessionState(
    private val capture: AudioCaptureController,
    private val modelStore: AsrModelStore? = null,
    private val mtModelStore: MtModelStore? = null,
    // The cloud MT mediation client is a seam: production uses the real HTTP client built from the
    // current [geminiConfig]; tests inject a fake to exercise gating without the network.
    private val cloudClientFactory: (GeminiFlashConfig) -> CloudMediationClient = ::HttpCloudMediationClient,
    /** On-device telemetry ring buffer. Defaults to a real sink; tests can inject a no-op or recording sink. */
    val telemetrySink: TelemetrySink = TelemetrySink(),
) {
    /** Per-session stable fields for every emitted [TelemetryEvent]. Mutable fields are updated
     *  when the user switches language/model/mode. */
    val telemetryContext: TelemetryContext = TelemetryContext.forDevice(
        appBuild = "0.1.0",
        sessionId = UUID.randomUUID().toString(),
    )

    // Compose snapshot state must only be mutated on the main thread. The capture/ASR/MT work runs on
    // background threads (flex-mic-capture, flex-wav-demo, flex-mt); their result callbacks route every
    // state write through here so we never trip CalledFromWrongThreadException / snapshot races. The
    // heavy work itself stays OFF the main thread — only the state writes are posted.
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Run [block] on the main thread. If we are already on the main looper the write happens inline
     * (so synchronous, main-thread callers see the update immediately); otherwise it is posted to the
     * main looper. Background callbacks should keep ALL Compose-state writes inside this helper.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * The active UI-chrome string catalog. Set by the composition root on each language switch so
     * that translation-reason strings produced inside [translateFinal] (which runs partially on
     * background threads but writes to state on the main thread) are always in the selected
     * language. Defaults to Russian so the session is usable before the first composition.
     */
    @Volatile
    var uiStrings: Strings = StringsRu

    private var _micPermission by mutableStateOf<OfflineFirstState>(capture.permissionState())
    val micPermission: OfflineFirstState get() = _micPermission

    private var _stats by mutableStateOf<CaptureStats?>(null)
    val stats: CaptureStats? get() = _stats

    private var _isCapturing by mutableStateOf(false)
    val isCapturing: Boolean get() = _isCapturing

    // Real energy-VAD state, driven by genuine mic frames via [AudioPipeline].
    private var _vadState by mutableStateOf(VadState.SILENCE)
    val vadState: VadState get() = _vadState
    val speechActive: Boolean get() = _vadState == VadState.SPEECH

    // Finalized utterances (joined) + the in-flight partial, both from the real recognizer.
    private var _finalTranscript by mutableStateOf("")
    val finalTranscript: String get() = _finalTranscript

    private var _partialTranscript by mutableStateOf("")
    val partialTranscript: String get() = _partialTranscript

    // ---- Machine translation (G005/WS4) -----------------------------------------------------

    /** Real model translation of the latest final transcript, or null until one is produced. */
    private var _translation by mutableStateOf<String?>(null)
    val translation: String? get() = _translation

    /** Honest gating reason when no real translation can be shown (never a fabricated string). */
    private var _translationReason by mutableStateOf<String?>(null)
    val translationReason: String? get() = _translationReason

    /** True while a real translation is running on the worker thread. */
    private var _translating by mutableStateOf(false)
    val translating: Boolean get() = _translating

    /** The MT model the user picked. Defaults to the on-device M2M-100 balanced model. */
    var selectedMtCandidate by mutableStateOf(MtCandidateRegistry.default)
        private set

    /** All selectable MT candidates for the picker (quality/speed/size metadata). */
    val mtCandidates: List<MtCandidate> get() = MtCandidateRegistry.candidates

    /** True when the selected on-device MT model has its files installed. */
    val mtModelInstalled: Boolean
        get() = mtSpecForSelection()?.let { spec -> mtModelStore?.isInstalled(spec) == true } ?: false

    /** Honest install report for the selected on-device MT model, or null. */
    fun inspectSelectedMtModel(): MtModelStore.InstallReport? {
        val store = mtModelStore ?: return null
        val spec = mtSpecForSelection() ?: return null
        return store.inspect(spec)
    }

    fun selectMtCandidate(candidate: MtCandidate) {
        if (candidate.id == selectedMtCandidate.id) return
        selectedMtCandidate = candidate
        releaseMtProvider()
        // Clear any prior translation unconditionally — the old text belongs to the old model and is
        // stale the moment the user switches. A re-translate (below) repopulates it only if there is a
        // final transcript to translate; otherwise the UI shows no stale cross-model result.
        _translation = null
        _translationReason = null
        syncTelemetryContext()
        // Re-translate the current final transcript through the newly selected model, if any.
        if (_finalTranscript.isNotBlank()) translateFinal(_finalTranscript)
    }

    /**
     * True when the on-device MT model backing [candidate] has its files installed. Resolves the spec
     * per [MtCandidate.modelId] (mirrors [inspectMtModel]/ModelsScreen), so the picker can show the
     * correct install state for EVERY row — not just the selected one. Cloud candidates and candidates
     * with no [MtCandidate.modelId] are never "installed".
     */
    fun isMtModelInstalled(candidate: MtCandidate): Boolean {
        val store = mtModelStore ?: return false
        val spec = candidate.modelId?.let { MtModelSpecs.forModelId(it) } ?: return false
        return store.isInstalled(spec)
    }

    /** Provider id of the ASR adapter selected for the current source language. */
    val asrProviderId: String get() = activeProvider()?.providerId ?: PlaceholderLocalAsrProvider().providerId

    /** True when a real offline model is installed for the selected source language. */
    val asrModelInstalled: Boolean
        get() = modelSpecForSource()?.let { spec -> modelStore?.isInstalled(spec) == true } ?: false

    /** Honest install report for a known ASR model id, or null if no store / no matching spec. */
    fun inspectAsrModel(modelId: String): AsrModelStore.InstallReport? {
        val store = modelStore ?: return null
        val spec = AsrModelSpecs.all.firstOrNull { it.modelId == modelId } ?: return null
        return store.inspect(spec)
    }

    /** Honest install report for a known MT model id, or null if no store / no matching spec. */
    fun inspectMtModel(modelId: String): MtModelStore.InstallReport? {
        val store = mtModelStore ?: return null
        val spec = MtModelSpecs.forModelId(modelId) ?: return null
        return store.inspect(spec)
    }

    /**
     * Resolve the on-device directory a download for [modelId] should land in, delegating to the
     * matching store so a completed download is immediately visible to the runtime. Both stores
     * share the same `filesDir/models/` root, so the resolved dir is the runtime's load path.
     */
    fun downloadDirFor(modelId: String): File? {
        AsrModelSpecs.all.firstOrNull { it.modelId == modelId }?.let { spec ->
            return modelStore?.modelDir(spec)
        }
        MtModelSpecs.forModelId(modelId)?.let { spec ->
            return mtModelStore?.modelDir(spec)
        }
        return null
    }

    private var _demoRunning by mutableStateOf(false)
    val demoRunning: Boolean get() = _demoRunning

    /** True when a bundled-by-push test clip exists for the selected source language's model. */
    val demoClipAvailable: Boolean
        get() = modelSpecForSource()?.let { spec -> demoClipFile(spec)?.isFile == true } ?: false

    /**
     * A2 self-test: feed a known test clip (e.g. `files/demo/ru_0.wav`) through the REAL
     * [SherpaOnnxAsrProvider], routing GENUINE recognizer output to the same transcript state.
     * This proves end-to-end real transcription on device without a live speaker. Never fabricates
     * text — whatever the recognizer decodes is shown verbatim.
     */
    fun runWavDemo() {
        if (_demoRunning || _isCapturing) return
        val store = modelStore ?: return
        val spec = modelSpecForSource() ?: return
        if (!store.isInstalled(spec)) return
        val clipFile = demoClipFile(spec) ?: return
        val clip = WavPcmReader.read(clipFile) ?: return

        _demoRunning = true
        _finalTranscript = ""
        _partialTranscript = ""
        _translation = null
        _translationReason = null
        Thread({
            val provider = SherpaOnnxAsrProvider(spec = spec, modelDir = store.modelDir(spec))
            try {
                var tsMs = 0L
                clip.pcm16.toList().chunked(DEMO_FRAME_SAMPLES).forEach { chunk ->
                    val frame = AudioFrame(
                        pcm16 = chunk.toShortArray(),
                        sampleRateHz = clip.sampleRateHz,
                        monotonicTsMs = tsMs,
                    )
                    val events = provider.accept(frame)
                    if (events.isNotEmpty()) applyTranscripts(events)
                    tsMs += DEMO_FRAME_MS
                }
                // Flush a final result for the tail of the clip (no more endpoints will fire).
                val tail = provider.accept(
                    AudioFrame(ShortArray(DEMO_FRAME_SAMPLES), clip.sampleRateHz, tsMs),
                )
                if (tail.isNotEmpty()) applyTranscripts(tail)
            } finally {
                provider.close()
                runOnMain { _demoRunning = false }
            }
        }, "flex-wav-demo").start()
    }

    private fun demoClipFile(spec: AsrModelSpec): File? {
        val store = modelStore ?: return null
        val clipName = when (spec) {
            AsrModelSpecs.RU_T_ONE -> "ru_0.wav"
            AsrModelSpecs.ZH_EN_BILINGUAL -> "zh_0.wav"
            else -> return null
        }
        return File(File(store.modelsRoot().parentFile, "demo"), clipName)
    }

    // Recreated on each capture start so the VAD/buffer start clean; null while idle.
    private var pipeline: AudioPipeline? = null
    private var sherpaProvider: SherpaOnnxAsrProvider? = null

    // Reused across translations (loading a model is expensive); recreated on model swap. Either
    // the M2M-100 ONNX engine (balanced tier) or the MiLMMT GGUF/llama.cpp engine (quality tier),
    // resolved from the selected candidate's [MtModelSpec] kind.
    private var mtProvider: TranslationProvider? = null

    // The cloud MT provider (Gemini Flash via backend mediation). Rebuilt when the backend endpoint
    // changes. Distinct from [mtProvider] because it carries no on-device model/session.
    private var cloudMtProvider: TranslationProvider? = null

    var sourceLanguage by mutableStateOf(FlexLanguage.RU)
        private set
    var targetLanguage by mutableStateOf(FlexLanguage.EN)
        private set

    private val _cloudStates = mutableStateOf(defaultCloudStates())
    val cloudStates: State<List<CloudOptInState>> = _cloudStates

    // WS5 cloud MT config. modelId default is config-driven (gemini-3.5-flash); the backend endpoint
    // is user-supplied on the Cloud screen and is blank until configured (then the gate blocks with
    // an honest "Не указан backend-endpoint" reason — never a fabricated translation).
    private var _geminiConfig by mutableStateOf(GeminiFlashConfig())
    val geminiConfig: GeminiFlashConfig get() = _geminiConfig

    /** The provider id of the cloud MT tier — kept in sync with the picker candidate. */
    val cloudMtProviderId: String get() = GeminiFlashTranslationProvider.PROVIDER_ID

    val languagePairLabel: String get() = "${sourceLanguage.code.uppercase()} → ${targetLanguage.code.uppercase()}"
    val languagePairKey: String get() = "${sourceLanguage.code}->${targetLanguage.code}"

    fun refreshPermission() {
        _micPermission = capture.permissionState()
    }

    fun selectSource(language: FlexLanguage) {
        sourceLanguage = language
        if (targetLanguage == language) targetLanguage = otherLanguage(language)
        syncTelemetryContext()
    }

    fun selectTarget(language: FlexLanguage) {
        targetLanguage = language
        if (sourceLanguage == language) sourceLanguage = otherLanguage(language)
        syncTelemetryContext()
    }

    fun swapLanguages() {
        val previousSource = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = previousSource
        syncTelemetryContext()
    }

    fun setUserConsent(providerId: String, consented: Boolean) {
        updateCloud(providerId) { it.copy(userConsented = consented) }
    }

    fun setDisclosureAccepted(providerId: String, accepted: Boolean) {
        updateCloud(providerId) { it.copy(disclosureAccepted = accepted) }
    }

    /**
     * Set the operator-run backend base URL for the cloud MT tier (e.g.
     * `https://flex-backend.example.com`). No Gemini key is ever stored — only OUR backend endpoint.
     * A swap rebuilds the cloud provider on next use so the new endpoint takes effect.
     */
    fun setGeminiBackendEndpoint(baseUrl: String) {
        val trimmed = baseUrl.trim()
        if (trimmed == _geminiConfig.backendBaseUrl) return
        _geminiConfig = _geminiConfig.copy(backendBaseUrl = trimmed)
        releaseMtProvider()
    }

    /** Start real mic capture. Transcript is driven by the real recognizer when a model exists. */
    fun startCapture() {
        refreshPermission()
        if (_micPermission !is OfflineFirstState.ReadyOfflineAsr) return
        _vadState = VadState.SILENCE
        _finalTranscript = ""
        _partialTranscript = ""
        _translation = null
        _translationReason = null

        syncTelemetryContext()
        telemetrySink.emitWith(telemetryContext, TelemetrySink.EVT_SESSION_START)

        val asrProvider = buildAsrProvider()
        val activePipeline = AudioPipeline(
            asrProvider = asrProvider,
            vad = EnergyVad(),
            // onUpdate fires on the capture thread; route the Compose-state writes to main.
            onUpdate = { snapshot ->
                runOnMain { _vadState = snapshot.vadState }
                // Emit VAD transition events on each state change.
                snapshot.latestEvent?.let { vadEvent ->
                    when (vadEvent) {
                        is VadEvent.SpeechStart -> telemetrySink.emitWith(
                            telemetryContext,
                            TelemetrySink.EVT_VAD_SPEECH_START,
                            monotonicTsMs = vadEvent.monotonicTsMs,
                        )
                        is VadEvent.SpeechEnd -> telemetrySink.emitWith(
                            telemetryContext,
                            TelemetrySink.EVT_VAD_SPEECH_END,
                            monotonicTsMs = vadEvent.monotonicTsMs,
                        )
                    }
                }
                if (snapshot.transcripts.isNotEmpty()) applyTranscripts(snapshot.transcripts)
            },
        )
        pipeline = activePipeline
        val started = capture.start(
            // onStats fires on the flex-mic-capture thread; route the Compose-state writes to main.
            onStats = { stats ->
                runOnMain {
                    _stats = stats
                    _isCapturing = stats.isCapturing
                }
            },
            onFrame = activePipeline::accept,
        )
        _isCapturing = started && capture.isCapturing()
        if (!_isCapturing) {
            pipeline = null
            releaseSherpa()
        }
    }

    fun stopCapture() {
        capture.stop()
        _isCapturing = false
        _vadState = VadState.SILENCE
        pipeline?.reset()
        pipeline = null
        releaseSherpa()
    }

    /**
     * Apply recognizer output to transcript state. Invoked from background threads (the capture
     * thread via [AudioPipeline.onUpdate] and the flex-wav-demo worker), so the whole body — including
     * the [translateFinal] hand-off — is marshalled to the main thread. Marshalling the full body (not
     * just individual writes) also serializes the demo→MT flow: the `_finalTranscript` read inside
     * [translateFinal] sees the value this same main-thread runnable just wrote.
     */
    private fun applyTranscripts(events: List<TranscriptEvent>) = runOnMain {
        events.forEach { event ->
            if (event.isFinal) {
                _finalTranscript = listOf(_finalTranscript, event.text)
                    .filter { it.isNotBlank() }
                    .joinToString(separator = " ")
                _partialTranscript = ""
                telemetrySink.emitWith(
                    telemetryContext,
                    TelemetrySink.EVT_ASR_FINAL,
                    monotonicTsMs = event.monotonicTsMs,
                    payload = mapOf("text_len" to event.text.length.toString()),
                )
                // Dialogue MT: a finalized utterance triggers a real translation into the pivot.
                if (event.text.isNotBlank()) translateFinal(_finalTranscript)
            } else {
                _partialTranscript = event.text
                telemetrySink.emitWith(
                    telemetryContext,
                    TelemetrySink.EVT_ASR_PARTIAL,
                    monotonicTsMs = event.monotonicTsMs,
                )
            }
        }
    }

    /**
     * Translate the latest [finalText] into the target language with the selected MT model, on a
     * worker thread. Real model output only — failures/missing models surface as an honest gating
     * reason, never a fabricated translation. Reason strings are produced via [uiStrings] so they
     * are always in the currently selected interface language.
     */
    private fun translateFinal(finalText: String) {
        val source = sourceLanguage.code
        val target = targetLanguage.code
        val candidate = selectedMtCandidate
        val pair = "$source->$target"

        // WS5 cloud tier: real Gemini Flash via backend mediation, hard-gated. No silent fallback —
        // if the gate blocks (no consent / disclosure / offline / no backend / no token) the provider
        // returns an honest reason and we surface it; we do NOT quietly route to an on-device model.
        if (candidate.execution == MtExecution.CLOUD) {
            telemetrySink.emitWith(
                telemetryContext,
                TelemetrySink.EVT_NETWORK_CALL,
                payload = mapOf("provider" to cloudMtProviderId, "pair" to pair),
            )
            val provider = cloudMtProvider ?: buildCloudMtProvider().also { cloudMtProvider = it }
            runTranslationOnWorker(provider, finalText, pair)
            return
        }

        val store = mtModelStore
        val spec = mtSpecForSelection()
        if (store == null || spec == null) {
            _translation = null
            _translationReason = uiStrings.mtEngineUnavailable(candidate.displayName)
            return
        }
        if (!store.isInstalled(spec)) {
            _translation = null
            _translationReason = uiStrings.mtModelNotInstalledReason(spec.modelId)
            return
        }

        val provider = mtProvider ?: buildMtProvider(spec, store).also { mtProvider = it }
        runTranslationOnWorker(provider, finalText, pair)
    }

    /**
     * Run [provider].translate on a worker thread and publish the honest result. Shared by the
     * on-device and cloud paths so both honor the same no-stale-clobber + no-fabrication contract.
     */
    private fun runTranslationOnWorker(provider: TranslationProvider, finalText: String, pair: String) {
        // Callers reach here on the main thread (via applyTranscripts / selectMtCandidate), so these
        // pre-translate writes are already main-thread safe.
        _translating = true
        _translationReason = null
        val mtStartTs = android.os.SystemClock.elapsedRealtime()
        telemetrySink.emitWith(
            telemetryContext,
            TelemetrySink.EVT_MT_START,
            monotonicTsMs = mtStartTs,
            payload = mapOf("provider" to provider.providerId, "pair" to pair),
        )
        Thread({
            // Heavy work stays off the main thread.
            val result: TranslationResult = provider.translate(finalText, pair, tierLabel())
            val latencyMs = android.os.SystemClock.elapsedRealtime() - mtStartTs
            // The stale-guard read AND the publish must happen together on ONE thread (the main thread)
            // so a concurrent flex-mt result can't read a half-updated _finalTranscript. Marshalling the
            // whole read-compare-write here also serializes overlapping translations onto the main looper.
            runOnMain {
                telemetrySink.emitWith(
                    telemetryContext,
                    TelemetrySink.EVT_MT_END,
                    payload = mapOf(
                        "provider" to provider.providerId,
                        "pair" to pair,
                        "latency_ms" to latencyMs.toString(),
                        "success" to (result.text != null).toString(),
                    ),
                )
                // Only overwrite if the transcript hasn't moved on (avoid stale results clobbering).
                if (_finalTranscript == finalText) {
                    _translation = result.text
                    _translationReason = result.unsupportedReason
                }
                _translating = false
            }
        }, "flex-mt").start()
    }

    private fun tierLabel(): String = "mid"

    private fun buildAsrProvider(): AsrProvider {
        releaseSherpa()
        val spec = modelSpecForSource()
        val store = modelStore
        if (spec != null && store != null && store.isInstalled(spec)) {
            val provider = SherpaOnnxAsrProvider(spec = spec, modelDir = store.modelDir(spec))
            sherpaProvider = provider
            return provider
        }
        return PlaceholderLocalAsrProvider()
    }

    private fun releaseSherpa() {
        sherpaProvider?.close()
        sherpaProvider = null
    }

    private fun releaseMtProvider() {
        mtProvider?.close()
        mtProvider = null
        cloudMtProvider?.close()
        cloudMtProvider = null
    }

    /**
     * Build the WS5 cloud MT provider: a [CloudCallGate] reading live opt-in state for this
     * provider id over the current [geminiConfig], plus a [CloudMediationClient] from the factory.
     * The app holds no Gemini key — only OUR backend endpoint from [geminiConfig].
     */
    private fun buildCloudMtProvider(): TranslationProvider {
        val config = _geminiConfig
        val gate = CloudCallGate(
            stateProvider = { id -> _cloudStates.value.firstOrNull { it.providerId == id } },
            config = config,
        )
        return GeminiFlashTranslationProvider(
            config = config,
            gate = gate,
            backend = cloudClientFactory(config),
            providerId = cloudMtProviderId,
        )
    }

    private fun activeProvider(): AsrProvider? = sherpaProvider

    private fun modelSpecForSource(): AsrModelSpec? = AsrModelSpecs.forLanguage(sourceLanguage.code)

    /** The on-device MT spec for the current selection, or null for cloud/unmapped candidates. */
    private fun mtSpecForSelection(): MtModelSpec? =
        selectedMtCandidate.modelId?.let { MtModelSpecs.forModelId(it) }

    /**
     * Build the [TranslationProvider] for [spec], selecting the engine by spec kind: M2M-100 ONNX
     * (balanced tier) or MiLMMT GGUF via llama.cpp (quality tier). Cloud candidates never reach
     * here (they gate earlier in [translateFinal]).
     */
    private fun buildMtProvider(spec: MtModelSpec, store: MtModelStore): TranslationProvider =
        when (spec) {
            is MtModelSpec.Seq2SeqOnnx -> M2m100MtProvider(spec = spec, modelDir = store.modelDir(spec))
            is MtModelSpec.Gguf -> MilmmtMtProvider(spec = spec, modelDir = store.modelDir(spec))
        }

    private fun updateCloud(providerId: String, transform: (CloudOptInState) -> CloudOptInState) {
        _cloudStates.value = _cloudStates.value.map { state ->
            if (state.providerId == providerId) transform(state) else state
        }
    }

    /**
     * Sync mutable [TelemetryContext] fields from current session state so every subsequent emit
     * carries up-to-date context. Call after any language, model, or mode change.
     */
    private fun syncTelemetryContext() {
        telemetryContext.languagePair = languagePairKey
        telemetryContext.runtimeId = asrProviderId
        telemetryContext.modelId = selectedMtCandidate.modelId ?: selectedMtCandidate.id
        telemetryContext.mode = if (selectedMtCandidate.execution == MtExecution.CLOUD) {
            TelemetrySink.MODE_CLOUD
        } else {
            TelemetrySink.MODE_OFFLINE
        }
    }

    private fun otherLanguage(language: FlexLanguage): FlexLanguage = when (language) {
        FlexLanguage.RU -> FlexLanguage.EN
        FlexLanguage.EN -> FlexLanguage.RU
        FlexLanguage.ZH -> FlexLanguage.RU  // ZH speaker → RU listener is the primary dialogue flow
    }

    private fun defaultCloudStates(): List<CloudOptInState> =
        CLOUD_PROVIDER_IDS.map { id ->
            CloudOptInState(
                providerId = id,
                userConsented = false,
                disclosureAccepted = false,
                credential = null,
                networkState = "offline",
            )
        }

    private companion object {
        // 20 ms frames at the clip's own rate are recomputed per clip; samples derived from ms.
        const val DEMO_FRAME_MS = 20L
        const val DEMO_FRAME_SAMPLES = 320 // 20 ms @ 16 kHz; smaller-rate clips just send shorter frames

        // Cloud opt-in cards (default OFF). The WS5 Gemini Flash MT tier leads; the three audio
        // adapters from the G005 scaffold follow (still stubs — gated, no real traffic).
        val CLOUD_PROVIDER_IDS = listOf(
            GeminiFlashTranslationProvider.PROVIDER_ID,
            "gemini-live-assistant",
            "cloud-stt-recognition-fallback",
            "gemini-batch-audio-enrichment",
        )
    }
}
