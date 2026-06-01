package dev.flextranslate.foundation

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Real streaming offline ASR over the native sherpa-onnx runtime.
 *
 * G004/WS3 (A2): this produces GENUINE recognizer output — never fabricated text. The recognizer
 * and its [OnlineStream] are created lazily on first [accept]; if model files are absent the
 * provider stays inert (returns `[]`) and reports [readiness] = [OfflineFirstState.MissingOfflinePack]
 * so the UI can gate honestly without crashing.
 *
 * Threading: [accept]/[reset] are called only from the single mic-capture thread (see
 * [AudioCaptureController]); the recognizer is not shared across threads.
 *
 * Support-matrix claims still require WS6 benchmark evidence — a working A2 demo is not a
 * launch-support claim.
 */
class SherpaOnnxAsrProvider(
    private val spec: AsrModelSpec,
    private val modelDir: File,
    private val numThreads: Int = DEFAULT_THREADS,
) : AsrProvider {

    override val providerId: String = "sherpa-onnx:${spec.modelId}"

    private sealed interface State {
        data object Uninitialized : State
        data class Ready(val recognizer: OnlineRecognizer, val stream: OnlineStream) : State
        data class Missing(val packId: String) : State
        data class Failed(val reason: String) : State
    }

    private var state: State = State.Uninitialized
    private var lastEmitted: String = ""

    /** Honest readiness for the UI/gating layer. Never throws. */
    fun readiness(): OfflineFirstState = when (val current = ensureInitialized()) {
        is State.Ready -> OfflineFirstState.ReadyOfflineAsr
        is State.Missing -> OfflineFirstState.MissingOfflinePack(current.packId)
        is State.Failed -> OfflineFirstState.MissingOfflinePack(spec.modelId)
        State.Uninitialized -> OfflineFirstState.MissingOfflinePack(spec.modelId)
    }

    override fun accept(frame: AudioFrame): List<TranscriptEvent> {
        val ready = ensureInitialized() as? State.Ready ?: return emptyList()
        val (recognizer, stream) = ready.recognizer to ready.stream
        return try {
            stream.acceptWaveform(toFloatSamples(frame.pcm16), frame.sampleRateHz)
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            val text = recognizer.getResult(stream).text.trim()
            val endpoint = recognizer.isEndpoint(stream)
            buildEvents(recognizer, stream, text, endpoint, frame.monotonicTsMs)
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed for ${spec.modelId}", t)
            state = State.Failed(t.message ?: t.javaClass.simpleName)
            emptyList()
        }
    }

    override fun reset() {
        (state as? State.Ready)?.let { ready ->
            runCatching { ready.recognizer.reset(ready.stream) }
        }
        lastEmitted = ""
    }

    /** Release native resources. Call when the provider is discarded (capture fully stopped). */
    fun close() {
        (state as? State.Ready)?.let { ready ->
            runCatching { ready.stream.release() }
            runCatching { ready.recognizer.release() }
        }
        state = State.Uninitialized
        lastEmitted = ""
    }

    private fun buildEvents(
        recognizer: OnlineRecognizer,
        stream: OnlineStream,
        text: String,
        endpoint: Boolean,
        tsMs: Long,
    ): List<TranscriptEvent> {
        val events = mutableListOf<TranscriptEvent>()
        if (endpoint) {
            // Finalize the current utterance with whatever the recognizer decoded, then reset so
            // the next utterance starts clean. Empty endpoints (pure silence) emit nothing.
            if (text.isNotEmpty()) {
                events += TranscriptEvent(text = text, isFinal = true, monotonicTsMs = tsMs)
            }
            recognizer.reset(stream)
            lastEmitted = ""
        } else if (text.isNotEmpty() && text != lastEmitted) {
            // Only emit a partial when the running hypothesis actually changed.
            lastEmitted = text
            events += TranscriptEvent(text = text, isFinal = false, monotonicTsMs = tsMs)
        }
        return events
    }

    private fun ensureInitialized(): State {
        val current = state
        if (current !is State.Uninitialized) return current
        val next = createRecognizerState()
        state = next
        return next
    }

    private fun createRecognizerState(): State {
        if (!spec.isInstalled(modelDir)) {
            return State.Missing(spec.modelId)
        }
        return runCatching {
            val recognizer = OnlineRecognizer(config = buildConfig())
            val stream = recognizer.createStream()
            Log.i(TAG, "sherpa-onnx recognizer ready: ${spec.modelId} @ ${modelDir.absolutePath}")
            State.Ready(recognizer, stream)
        }.getOrElse { t ->
            Log.e(TAG, "recognizer init failed for ${spec.modelId}", t)
            State.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun buildConfig(): OnlineRecognizerConfig {
        val tokensPath = File(modelDir, tokensFileName()).absolutePath
        val modelConfig = when (spec) {
            is AsrModelSpec.ToneCtc -> OnlineModelConfig(
                toneCtc = OnlineToneCtcModelConfig(model = File(modelDir, spec.model).absolutePath),
                tokens = tokensPath,
                numThreads = numThreads,
            )

            is AsrModelSpec.Transducer -> OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, spec.encoder).absolutePath,
                    decoder = File(modelDir, spec.decoder).absolutePath,
                    joiner = File(modelDir, spec.joiner).absolutePath,
                ),
                tokens = tokensPath,
                modelType = spec.modelType,
                numThreads = numThreads,
            )
        }
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            enableEndpoint = true,
        )
    }

    private fun tokensFileName(): String = when (spec) {
        is AsrModelSpec.ToneCtc -> spec.tokens
        is AsrModelSpec.Transducer -> spec.tokens
    }

    private companion object {
        const val TAG = "SherpaOnnxAsr"
        const val DEFAULT_THREADS = 2
        const val SAMPLE_RATE_HZ = 16_000
        const val FEATURE_DIM = 80
        const val PCM16_FULL_SCALE = 32_768.0f

        /** Int16 PCM → normalized Float in [-1, 1], the input sherpa-onnx expects. */
        fun toFloatSamples(pcm16: ShortArray): FloatArray =
            FloatArray(pcm16.size) { index -> pcm16[index] / PCM16_FULL_SCALE }
    }
}
