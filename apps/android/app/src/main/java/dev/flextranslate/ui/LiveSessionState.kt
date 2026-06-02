package dev.flextranslate.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import dev.flextranslate.foundation.GeminiCredentialMode
import dev.flextranslate.foundation.GeminiDirectClient
import dev.flextranslate.foundation.GeminiFlashConfig
import dev.flextranslate.foundation.GeminiFlashTranslationProvider
import dev.flextranslate.foundation.GeminiKeyStore
import dev.flextranslate.foundation.HttpCloudMediationClient
import dev.flextranslate.foundation.M2m100MtProvider
import dev.flextranslate.foundation.MilmmtMtProvider
import dev.flextranslate.foundation.MtCandidate
import dev.flextranslate.foundation.MtCandidateRegistry
import dev.flextranslate.foundation.MtExecution
import dev.flextranslate.foundation.MtModelSpec
import dev.flextranslate.foundation.MtRoutingMode
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

/** Языки фазы 0 — RU/EN/ZH. */
enum class FlexLanguage(val code: String, val label: String) {
    RU("ru", "Русский"),
    EN("en", "English"),
    ZH("zh", "中文"),
}

/**
 * Лёгкий держатель состояния UI-сессии. Владеет реальным [AudioCaptureController], а если для
 * выбранного исходного языка установлена офлайн-модель — ещё и реальным [SherpaOnnxAsrProvider].
 *
 * G004/WS3 (A2): транскрипт — это НАСТОЯЩИЙ вывод распознавателя: partial во время речи, final по
 * endpoint. Без установленной модели провайдер — заглушка [PlaceholderLocalAsrProvider] (отдаёт
 * `[]`), а готовность отражает [OfflineFirstState.MissingOfflinePack]. Выдуманного текста не бывает.
 *
 * Это не ViewModel: создаётся через remember в MainActivity, чтобы пережить рекомпозицию; Activity
 * прокидывает результаты разрешений и останавливает по жизненному циклу.
 *
 * [uiStrings] обновляет composition root при каждой смене языка интерфейса — чтобы строки с причиной
 * перевода (их собирают вне Compose-дерева) были на выбранном языке.
 */
class LiveSessionState(
    private val capture: AudioCaptureController,
    private val modelStore: AsrModelStore? = null,
    private val mtModelStore: MtModelStore? = null,
    // Точка подмены для облачного MT: в проде — реальный HTTP-клиент на текущем [geminiConfig],
    // в тестах подсовываем fake, чтобы проверять гейтинг без сети.
    private val cloudClientFactory: (GeminiFlashConfig) -> CloudMediationClient = ::HttpCloudMediationClient,
    // Защищённое хранилище ключа для BYOK (режим OWN_KEY). В проде — AndroidGeminiKeyStore, в тестах
    // можно подменить. null означает, что путь OWN_KEY всегда заблокирован (ключа нет).
    private val geminiKeyStore: GeminiKeyStore? = null,
    /** Кольцевой буфер телеметрии на устройстве. По умолчанию реальный; в тестах можно no-op или записывающий. */
    val telemetrySink: TelemetrySink = TelemetrySink(),
) {
    /** Стабильные на всю сессию поля для каждого [TelemetryEvent]. Изменяемые поля обновляются при
     *  смене языка/модели/режима. */
    val telemetryContext: TelemetryContext = TelemetryContext.forDevice(
        appBuild = "0.1.0",
        sessionId = UUID.randomUUID().toString(),
    )

    // Compose snapshot-state можно менять только из главного потока. Захват/ASR/MT крутятся в фоновых
    // потоках (flex-mic-capture, flex-wav-demo, flex-mt); их колбэки прогоняют каждую запись состояния
    // через этот хэндлер, чтобы не словить CalledFromWrongThreadException и гонки snapshot. Тяжёлая
    // работа остаётся в фоне — на главный поток постятся только записи состояния.
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Выполнить [block] в главном потоке. Если мы уже на главном looper'е — запись идёт сразу (чтобы
     * синхронный вызов с главного потока увидел обновление мгновенно); иначе постим на главный looper.
     * Фоновые колбэки должны держать ВСЕ записи Compose-состояния внутри этого хелпера.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * Активный каталог строк интерфейса. Composition root переставляет его при каждой смене языка,
     * чтобы строки с причиной перевода из [translateFinal] (часть работы — в фоне, но запись в
     * состояние идёт с главного потока) всегда были на выбранном языке. По умолчанию русский —
     * чтобы сессия работала ещё до первой композиции.
     */
    @Volatile
    var uiStrings: Strings = StringsRu

    private var _micPermission by mutableStateOf<OfflineFirstState>(capture.permissionState())
    val micPermission: OfflineFirstState get() = _micPermission

    private var _stats by mutableStateOf<CaptureStats?>(null)
    val stats: CaptureStats? get() = _stats

    private var _isCapturing by mutableStateOf(false)
    val isCapturing: Boolean get() = _isCapturing

    // Состояние реального energy-VAD, который кормится настоящими кадрами с микрофона через [AudioPipeline].
    private var _vadState by mutableStateOf(VadState.SILENCE)
    val vadState: VadState get() = _vadState
    val speechActive: Boolean get() = _vadState == VadState.SPEECH

    // Финализированные фразы (склеенные) + текущий partial — всё с реального распознавателя.
    private var _finalTranscript by mutableStateOf("")
    val finalTranscript: String get() = _finalTranscript

    private var _partialTranscript by mutableStateOf("")
    val partialTranscript: String get() = _partialTranscript

    // ---- Машинный перевод (G005/WS4) ---------------------------------------------------------

    /** Перевод последнего final-транскрипта реальной моделью, или null пока перевода нет. */
    private var _translation by mutableStateOf<String?>(null)
    val translation: String? get() = _translation

    /** Честная причина, когда реального перевода показать нельзя (никогда не выдуманная строка). */
    private var _translationReason by mutableStateOf<String?>(null)
    val translationReason: String? get() = _translationReason

    /** true, пока реальный перевод крутится в рабочем потоке. */
    private var _translating by mutableStateOf(false)
    val translating: Boolean get() = _translating

    // ---- Лог диалога (G-DIALOGUE) ------------------------------------------------------------

    /**
     * Упорядоченный список реплик. Запись добавляется с главного потока, когда прилетает ASR final;
     * слот перевода заполняется асинхронно (тоже с главного потока, через [runOnMain]), когда
     * закончит MT-воркер. Compose наблюдает за списком через snapshot-состояние [mutableStateListOf].
     */
    private val _conversationLog = mutableStateListOf<DialogueTurn>()

    /** Только-чтение представление лога диалога. */
    val conversationLog: List<DialogueTurn> get() = _conversationLog

    /**
     * Очистить весь лог диалога. Идемпотентно. Вызывать только с главного потока (то же правило, что
     * для всех записей состояния — из фонового колбэка оборачивай в [runOnMain]).
     */
    fun clearDialogue() {
        _conversationLog.clear()
    }

    /** MT-модель, выбранная пользователем. По умолчанию — сбалансированная on-device M2M-100. */
    var selectedMtCandidate by mutableStateOf(MtCandidateRegistry.default)
        private set

    /**
     * Как маршрутизировать каждый запрос на перевод. По умолчанию [MtRoutingMode.AUTO]: Gemini Flash,
     * когда облачный гейт пропускает (онлайн + согласие + credential), иначе выбранная on-device
     * модель. Offline-first: нет сети / нет согласия / нет credential — значит on-device, без тихого
     * похода в облако.
     */
    var selectedRoutingMode by mutableStateOf(MtRoutingMode.AUTO)
        private set

    /** Сменить режим маршрутизации. Сбрасывает устаревший перевод, чтобы UI не рассинхронился. */
    fun selectRoutingMode(mode: MtRoutingMode) {
        if (mode == selectedRoutingMode) return
        selectedRoutingMode = mode
        _translation = null
        _translationReason = null
        syncTelemetryContext()
        if (_finalTranscript.isNotBlank()) translateFinal(_finalTranscript)
    }

    /** Все MT-кандидаты для пикера (метаданные качество/скорость/размер). */
    val mtCandidates: List<MtCandidate> get() = MtCandidateRegistry.candidates

    /** true, когда файлы выбранной on-device MT-модели установлены. */
    val mtModelInstalled: Boolean
        get() = mtSpecForSelection()?.let { spec -> mtModelStore?.isInstalled(spec) == true } ?: false

    /** Честный отчёт об установке выбранной on-device MT-модели, или null. */
    fun inspectSelectedMtModel(): MtModelStore.InstallReport? {
        val store = mtModelStore ?: return null
        val spec = mtSpecForSelection() ?: return null
        return store.inspect(spec)
    }

    fun selectMtCandidate(candidate: MtCandidate) {
        if (candidate.id == selectedMtCandidate.id) return
        selectedMtCandidate = candidate
        releaseMtProvider()
        // Безусловно сбрасываем прошлый перевод — старый текст принадлежит старой модели и устаревает
        // в момент переключения. Повторный перевод (ниже) заполнит его, только если есть final-транскрипт;
        // иначе UI не покажет устаревший результат от другой модели.
        _translation = null
        _translationReason = null
        syncTelemetryContext()
        // Перегоняем текущий final-транскрипт через только что выбранную модель, если он есть.
        if (_finalTranscript.isNotBlank()) translateFinal(_finalTranscript)
    }

    /**
     * true, когда установлены файлы on-device MT-модели за [candidate]. Спека ищется по
     * [MtCandidate.modelId] (как в [inspectMtModel]/ModelsScreen), чтобы пикер показывал правильный
     * статус установки для КАЖДОЙ строки, а не только для выбранной. Облачные кандидаты и кандидаты
     * без [MtCandidate.modelId] «установленными» не считаются.
     */
    fun isMtModelInstalled(candidate: MtCandidate): Boolean {
        val store = mtModelStore ?: return false
        val spec = candidate.modelId?.let { MtModelSpecs.forModelId(it) } ?: return false
        return store.isInstalled(spec)
    }

    /** id ASR-адаптера, выбранного под текущий исходный язык. */
    val asrProviderId: String get() = activeProvider()?.providerId ?: PlaceholderLocalAsrProvider().providerId

    /** true, когда для выбранного исходного языка установлена реальная офлайн-модель. */
    val asrModelInstalled: Boolean
        get() = modelSpecForSource()?.let { spec -> modelStore?.isInstalled(spec) == true } ?: false

    /** Честный отчёт об установке по id ASR-модели, или null если нет store / нет подходящей спеки. */
    fun inspectAsrModel(modelId: String): AsrModelStore.InstallReport? {
        val store = modelStore ?: return null
        val spec = AsrModelSpecs.all.firstOrNull { it.modelId == modelId } ?: return null
        return store.inspect(spec)
    }

    /** Честный отчёт об установке по id MT-модели, или null если нет store / нет подходящей спеки. */
    fun inspectMtModel(modelId: String): MtModelStore.InstallReport? {
        val store = mtModelStore ?: return null
        val spec = MtModelSpecs.forModelId(modelId) ?: return null
        return store.inspect(spec)
    }

    /**
     * Куда на устройстве должна лечь загрузка [modelId]. Делегируем нужному store, чтобы скачанное
     * сразу было видно рантайму. Оба store держат один корень `filesDir/models/`, так что эта папка —
     * и есть путь, откуда рантайм грузит модель.
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

    /** true, когда для модели выбранного исходного языка есть пушнутый на устройство тестовый клип. */
    val demoClipAvailable: Boolean
        get() = modelSpecForSource()?.let { spec -> demoClipFile(spec)?.isFile == true } ?: false

    /**
     * Самопроверка A2: прогоняем известный клип (например `files/demo/ru_0.wav`) через РЕАЛЬНЫЙ
     * [SherpaOnnxAsrProvider], направляя НАСТОЯЩИЙ вывод распознавателя в то же состояние транскрипта.
     * Доказывает сквозное реальное распознавание на устройстве без живого диктора. Текст не выдумывается —
     * показываем дословно то, что декодировал распознаватель.
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
                // Выжимаем final для хвоста клипа — endpoint'ов больше не будет.
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

    // Пересоздаётся при каждом старте захвата, чтобы VAD/буфер начинали с чистого листа; null в простое.
    private var pipeline: AudioPipeline? = null
    private var sherpaProvider: SherpaOnnxAsrProvider? = null

    // Переиспользуется между переводами (загрузка модели дорогая); пересоздаётся при смене модели.
    // Это либо движок M2M-100 ONNX (сбалансированный тир), либо MiLMMT GGUF/llama.cpp (качественный
    // тир) — выбор по виду [MtModelSpec] выбранного кандидата.
    private var mtProvider: TranslationProvider? = null

    // Облачный MT-провайдер (Gemini Flash через backend-медиацию). Пересобирается при смене backend-
    // endpoint'а. Отдельно от [mtProvider], потому что не тащит за собой on-device модель/сессию.
    private var cloudMtProvider: TranslationProvider? = null

    var sourceLanguage by mutableStateOf(FlexLanguage.RU)
        private set
    var targetLanguage by mutableStateOf(FlexLanguage.EN)
        private set

    private val _cloudStates = mutableStateOf(defaultCloudStates())
    val cloudStates: State<List<CloudOptInState>> = _cloudStates

    // Конфиг облачного MT (WS5). modelId по умолчанию из конфига (gemini-3.5-flash); backend-endpoint
    // пользователь задаёт на экране Cloud, до этого он пустой (и тогда гейт честно блокирует с причиной
    // «Не указан backend-endpoint» — никакого выдуманного перевода).
    private var _geminiConfig by mutableStateOf(GeminiFlashConfig())
    val geminiConfig: GeminiFlashConfig get() = _geminiConfig

    /** id провайдера облачного MT-тира — держим в синхроне с кандидатом из пикера. */
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
     * Задать базовый URL нашего backend'а для облачного MT-тира (например
     * `https://flex-backend.example.com`). Ключ Gemini тут не хранится — только адрес НАШЕГО backend'а.
     * Смена пересоберёт облачный провайдер при следующем использовании, чтобы новый endpoint вступил в силу.
     */
    fun setGeminiBackendEndpoint(baseUrl: String) {
        val trimmed = baseUrl.trim()
        if (trimmed == _geminiConfig.backendBaseUrl) return
        _geminiConfig = _geminiConfig.copy(backendBaseUrl = trimmed)
        releaseMtProvider()
    }

    /**
     * Переключить режим credential для облачного MT-тира (BACKEND_MEDIATION ↔ OWN_KEY).
     * Пересобирает облачный провайдер при следующем использовании, чтобы гейт и транспорт сменились разом.
     */
    fun setGeminiCredentialMode(mode: GeminiCredentialMode) {
        if (mode == _geminiConfig.credentialMode) return
        _geminiConfig = _geminiConfig.copy(credentialMode = mode)
        releaseMtProvider()
    }

    /**
     * Сохранить API-ключ Gemini пользователя в шифрованное хранилище (режим BYOK / OWN_KEY).
     * Ключ кладётся через [GeminiKeyStore] (EncryptedSharedPreferences на устройстве) и НИКОГДА не
     * логируется, не печатается и не попадает в текст ошибок. Облачный провайдер пересобирается,
     * чтобы новый ключ подхватился сразу.
     *
     * @param apiKey Ключ от пользователя. Пустые значения игнорируются (для удаления — [clearGeminiOwnKey]).
     */
    fun saveGeminiOwnKey(apiKey: String) {
        if (apiKey.isBlank()) return
        geminiKeyStore?.saveKey(apiKey)
        releaseMtProvider()
    }

    /** Удалить сохранённый API-ключ Gemini и пересобрать облачный провайдер. */
    fun clearGeminiOwnKey() {
        geminiKeyStore?.clearKey()
        releaseMtProvider()
    }

    /** true, когда сейчас в шифрованном хранилище лежит ключ Gemini для режима BYOK. */
    val geminiOwnKeyStored: Boolean
        get() = geminiKeyStore?.hasKey() == true

    /** Запустить реальный захват микрофона. Транскрипт ведёт реальный распознаватель, если модель есть. */
    fun startCapture() {
        refreshPermission()
        if (_micPermission !is OfflineFirstState.ReadyOfflineAsr) return
        _vadState = VadState.SILENCE
        _finalTranscript = ""
        _partialTranscript = ""
        _translation = null
        _translationReason = null
        _conversationLog.clear()

        syncTelemetryContext()
        telemetrySink.emitWith(telemetryContext, TelemetrySink.EVT_SESSION_START)

        val asrProvider = buildAsrProvider()
        val activePipeline = AudioPipeline(
            asrProvider = asrProvider,
            vad = EnergyVad(),
            // onUpdate срабатывает в потоке захвата; записи Compose-состояния гоним на главный поток.
            onUpdate = { snapshot ->
                runOnMain { _vadState = snapshot.vadState }
                // Шлём события перехода VAD на каждую смену состояния.
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
            // onStats срабатывает в потоке flex-mic-capture; записи Compose-состояния гоним на главный поток.
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
     * Применить вывод распознавателя к состоянию транскрипта. Вызывается из фоновых потоков (поток
     * захвата через [AudioPipeline.onUpdate] и воркер flex-wav-demo), поэтому всё тело — включая передачу
     * в [translateFinal] — выполняется на главном потоке. Маршаллинг всего тела (а не отдельных записей)
     * заодно сериализует поток demo→MT: чтение `_finalTranscript` внутри [translateFinal] видит значение,
     * которое только что записал этот же runnable на главном потоке.
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
                // Диалоговый MT: финализированная фраза создаёт реплику в логе диалога и запускает
                // реальный перевод на язык собеседника.
                if (event.text.isNotBlank()) {
                    val spokenLang = sourceLanguage
                    val counterpartLang = targetLanguage
                    val turn = DialogueTurn(
                        id = UUID.randomUUID().toString(),
                        monotonicTs = event.monotonicTsMs,
                        spokenLanguage = spokenLang,
                        originalText = event.text,
                        translatedText = null,
                        translationReason = null,
                        translationLanguage = counterpartLang,
                    )
                    _conversationLog.add(turn)
                    translateFinal(_finalTranscript)
                    translateTurn(turn, event.text, spokenLang, counterpartLang)
                }
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
     * true, если облачный гейт прямо сейчас пропустил бы вызов Gemini: для провайдера Gemini Flash
     * есть согласие + принят disclosure + сеть онлайн + есть credential (backend-endpoint либо свой
     * ключ). Это проба только на чтение — реального вызова НЕ делает. Используется в [MtRoutingMode.AUTO],
     * чтобы выбрать движок.
     */
    fun isCloudUsable(): Boolean {
        val gate = CloudCallGate(
            stateProvider = { id -> _cloudStates.value.firstOrNull { it.providerId == id } },
            config = _geminiConfig,
            keyStore = geminiKeyStore,
        )
        return gate.evaluate(cloudMtProviderId, System.currentTimeMillis()) is CloudCallGate.Decision.Allowed
    }

    /**
     * Выбрать провайдера для перевода с учётом текущего [selectedRoutingMode]:
     *
     * - [MtRoutingMode.AUTO]: Gemini если [isCloudUsable], иначе on-device.
     * - [MtRoutingMode.ON_DEVICE]: всегда on-device.
     * - [MtRoutingMode.CLOUD]: всегда Gemini (гейт всё равно отработает внутри провайдера; если
     *   заблокирует — вернётся честная причина, без тихого отката на on-device).
     *
     * Возвращает [ResolvedEngine] либо с готовым провайдером, либо с причиной блокировки
     * (никогда не оба и не пусто).
     */
    private fun resolveEngineForTranslation(): ResolvedEngine {
        val candidate = selectedMtCandidate
        val mode = selectedRoutingMode

        val useCloud = when (mode) {
            MtRoutingMode.CLOUD -> true
            MtRoutingMode.AUTO -> isCloudUsable()
            MtRoutingMode.ON_DEVICE -> false
        }

        if (useCloud) {
            val provider = cloudMtProvider ?: buildCloudMtProvider().also { cloudMtProvider = it }
            return ResolvedEngine.Cloud(provider)
        }

        // Путь on-device.
        val store = mtModelStore
        val spec = mtSpecForSelection()
        if (store == null || spec == null) {
            return ResolvedEngine.Blocked(uiStrings.mtEngineUnavailable(candidate.displayName))
        }
        if (!store.isInstalled(spec)) {
            return ResolvedEngine.Blocked(uiStrings.mtModelNotInstalledReason(spec.modelId))
        }
        val provider = mtProvider ?: buildMtProvider(spec, store).also { mtProvider = it }
        return ResolvedEngine.OnDevice(provider, engineLabel = spec.modelId)
    }

    /** Результат [resolveEngineForTranslation]. */
    private sealed interface ResolvedEngine {
        /** Используем облачный провайдер Gemini Flash. */
        data class Cloud(val provider: TranslationProvider) : ResolvedEngine
        /** Используем on-device провайдер; [engineLabel] показывается в бейдже реплики. */
        data class OnDevice(val provider: TranslationProvider, val engineLabel: String) : ResolvedEngine
        /** Движок недоступен; [reason] — честное локализованное сообщение для UI. */
        data class Blocked(val reason: String) : ResolvedEngine
    }

    /**
     * Перевести последний [finalText] на целевой язык выбранной MT-моделью, в рабочем потоке.
     * Только реальный вывод модели — сбои/отсутствие модели всплывают честной причиной, а не выдуманным
     * переводом. Строки причин собираются через [uiStrings], так что всегда на текущем языке интерфейса.
     *
     * Режим AUTO: облачный гейт проверяется в момент вызова — Gemini если можно, иначе on-device.
     * Тихих походов в облако нет: нужны и согласие, и сеть, и credential.
     */
    private fun translateFinal(finalText: String) {
        val source = sourceLanguage.code
        val target = targetLanguage.code
        val pair = "$source->$target"

        when (val engine = resolveEngineForTranslation()) {
            is ResolvedEngine.Cloud -> {
                telemetrySink.emitWith(
                    telemetryContext,
                    TelemetrySink.EVT_NETWORK_CALL,
                    payload = mapOf("provider" to cloudMtProviderId, "pair" to pair),
                )
                runTranslationOnWorker(engine.provider, finalText, pair)
            }
            is ResolvedEngine.OnDevice -> runTranslationOnWorker(engine.provider, finalText, pair)
            is ResolvedEngine.Blocked -> {
                _translation = null
                _translationReason = engine.reason
            }
        }
    }

    /**
     * Выполнить [provider].translate в рабочем потоке и опубликовать честный результат. Общий код для
     * on-device и облачного путей — оба соблюдают один контракт: не затирать устаревшим, не выдумывать.
     */
    private fun runTranslationOnWorker(provider: TranslationProvider, finalText: String, pair: String) {
        // Сюда попадают с главного потока (через applyTranscripts / selectMtCandidate), так что эти
        // записи до перевода уже безопасны по потоку.
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
            // Тяжёлая работа держится в стороне от главного потока.
            val result: TranslationResult = provider.translate(finalText, pair, tierLabel())
            val latencyMs = android.os.SystemClock.elapsedRealtime() - mtStartTs
            // Проверка на устаревание И публикация должны идти вместе в ОДНОМ потоке (главном), чтобы
            // параллельный результат flex-mt не прочитал наполовину обновлённый _finalTranscript. Весь
            // read-compare-write тут заодно сериализует пересекающиеся переводы на главном looper'е.
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
                // Перезаписываем, только если транскрипт не ушёл вперёд (чтобы устаревшее не затёрло свежее).
                if (_finalTranscript == finalText) {
                    _translation = result.text
                    _translationReason = result.unsupportedReason
                }
                _translating = false
            }
        }, "flex-mt").start()
    }

    /**
     * Перевести [utteranceText] одной реплики диалога с [spokenLang] на [counterpartLang] и обновить
     * соответствующую запись в [_conversationLog] честным результатом. Перевод использует ту же
     * политику маршрутизации, что и [translateFinal] (AUTO / ON_DEVICE / CLOUD) — чтобы оба пути были
     * согласованы.
     *
     * Пара языков для провайдера — `spokenLang.code->counterpartLang.code`, чтобы модель всегда
     * переводила в том направлении, в котором реально говорил человек, независимо от текущих
     * [sourceLanguage]/[targetLanguage], которые к моменту завершения воркера могли уже поменять местами.
     */
    private fun translateTurn(
        turn: DialogueTurn,
        utteranceText: String,
        spokenLang: FlexLanguage,
        counterpartLang: FlexLanguage,
    ) {
        val pair = "${spokenLang.code}->${counterpartLang.code}"

        when (val engine = resolveEngineForTranslation()) {
            is ResolvedEngine.Cloud ->
                runTurnTranslationOnWorker(engine.provider, turn, utteranceText, pair, engineLabel = null)
            is ResolvedEngine.OnDevice ->
                runTurnTranslationOnWorker(engine.provider, turn, utteranceText, pair, engineLabel = engine.engineLabel)
            is ResolvedEngine.Blocked ->
                runOnMain { updateTurnResult(turn.id, null, engine.reason, engineLabel = null) }
        }
    }

    /**
     * Выполнить перевод одной реплики в рабочем потоке и опубликовать честный результат обратно в
     * нужную [DialogueTurn] в [_conversationLog]. [engineLabel] — человекочитаемое имя движка (например
     * id модели для on-device, или null для облака — тогда вызывающий подставляет id провайдера).
     */
    private fun runTurnTranslationOnWorker(
        provider: TranslationProvider,
        turn: DialogueTurn,
        utteranceText: String,
        pair: String,
        engineLabel: String?,
    ) {
        val isCloud = provider.providerId == cloudMtProviderId
        Thread({
            val result: TranslationResult = provider.translate(utteranceText, pair, tierLabel())
            runOnMain {
                val label = when {
                    result.text == null -> null
                    isCloud -> GeminiFlashTranslationProvider.PROVIDER_ID
                    else -> engineLabel
                }
                updateTurnResult(turn.id, result.text, result.unsupportedReason, engineLabel = label)
            }
        }, "flex-mt-turn").start()
    }

    /**
     * Найти реплику с [turnId] в [_conversationLog] и заменить её результатом перевода. Вызывать только
     * с главного потока (все изменения [_conversationLog] — только с главного потока).
     */
    private fun updateTurnResult(turnId: String, text: String?, reason: String?, engineLabel: String? = null) {
        val index = _conversationLog.indexOfFirst { it.id == turnId }
        if (index < 0) return
        _conversationLog[index] = _conversationLog[index].withTranslation(text, reason, engineLabel)
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
     * Собрать облачный MT-провайдер (WS5) на текущем конфиге и режиме credential.
     *
     * - BACKEND_MEDIATION: гейту нужен backend-endpoint + эфемерный токен; ключ здесь не трогаем.
     * - OWN_KEY: гейту нужен шифрованный ключ в [geminiKeyStore]; прямой клиент шлёт POST в Gemini.
     *
     * Приложение никогда не держит ключ Gemini в памяти — [GeminiKeyStore.loadKey] зовётся just-in-time
     * внутри [GeminiFlashTranslationProvider.translate] в рабочем потоке.
     */
    private fun buildCloudMtProvider(): TranslationProvider {
        val config = _geminiConfig
        val gate = CloudCallGate(
            stateProvider = { id -> _cloudStates.value.firstOrNull { it.providerId == id } },
            config = config,
            keyStore = geminiKeyStore,
        )
        return GeminiFlashTranslationProvider(
            config = config,
            gate = gate,
            backend = cloudClientFactory(config),
            directClient = GeminiDirectClient(config),
            keyStore = geminiKeyStore,
            providerId = cloudMtProviderId,
        )
    }

    private fun activeProvider(): AsrProvider? = sherpaProvider

    private fun modelSpecForSource(): AsrModelSpec? = AsrModelSpecs.forLanguage(sourceLanguage.code)

    /** Спека on-device MT для текущего выбора, или null для облачных/несопоставленных кандидатов. */
    private fun mtSpecForSelection(): MtModelSpec? =
        selectedMtCandidate.modelId?.let { MtModelSpecs.forModelId(it) }

    /**
     * Собрать [TranslationProvider] под [spec], выбирая движок по виду спеки: M2M-100 ONNX
     * (сбалансированный тир) либо MiLMMT GGUF через llama.cpp (качественный тир). Облачные кандидаты
     * сюда не доходят (отсекаются раньше в [translateFinal]).
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
     * Подтянуть изменяемые поля [TelemetryContext] из текущего состояния сессии, чтобы каждый следующий
     * emit нёс актуальный контекст. Вызывать после любой смены языка, модели или режима.
     */
    private fun syncTelemetryContext() {
        telemetryContext.languagePair = languagePairKey
        telemetryContext.runtimeId = asrProviderId
        telemetryContext.modelId = selectedMtCandidate.modelId ?: selectedMtCandidate.id
        telemetryContext.mode = when (selectedRoutingMode) {
            MtRoutingMode.CLOUD -> TelemetrySink.MODE_CLOUD
            MtRoutingMode.AUTO -> if (isCloudUsable()) TelemetrySink.MODE_CLOUD else TelemetrySink.MODE_OFFLINE
            MtRoutingMode.ON_DEVICE -> TelemetrySink.MODE_OFFLINE
        }
    }

    private fun otherLanguage(language: FlexLanguage): FlexLanguage = when (language) {
        FlexLanguage.RU -> FlexLanguage.EN
        FlexLanguage.EN -> FlexLanguage.RU
        FlexLanguage.ZH -> FlexLanguage.RU  // китаец → русский слушатель — основной сценарий диалога
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
        // Кадры по 20 мс на собственной частоте клипа пересчитываются под каждый клип; число сэмплов из мс.
        const val DEMO_FRAME_MS = 20L
        const val DEMO_FRAME_SAMPLES = 320 // 20 мс @ 16 кГц; клипы с меньшей частотой просто шлют кадры покороче

        // Карточки облачного opt-in (по умолчанию ВЫКЛ). Впереди MT-тир Gemini Flash (WS5); за ним три
        // аудио-адаптера из каркаса G005 (пока заглушки — за гейтом, реального трафика нет).
        val CLOUD_PROVIDER_IDS = listOf(
            GeminiFlashTranslationProvider.PROVIDER_ID,
            "gemini-live-assistant",
            "cloud-stt-recognition-fallback",
            "gemini-batch-audio-enrichment",
        )
    }
}
