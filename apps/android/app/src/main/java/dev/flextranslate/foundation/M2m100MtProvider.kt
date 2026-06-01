package dev.flextranslate.foundation

import android.util.Log
import java.io.File

/**
 * Real on-device machine translation via M2M-100 ([M2m100OnnxEngine]) implementing
 * [TranslationProvider].
 *
 * G005/WS4 (A2): every non-null [TranslationResult.text] is GENUINE model output. There is no
 * fabricated/canned translation anywhere — if the model files are absent or a translation fails,
 * the provider returns an honest [TranslationResult.unsupportedReason] (gated), never fake text.
 *
 * The engine is created lazily on first use and reused across calls (loading two ONNX sessions is
 * expensive). Direction comes from a `"src->tgt"` [languagePair] string, e.g. `"en->ru"`. M2M-100
 * handles RU↔EN and RU↔ZH directly via a forced target-language token — no English pivot.
 *
 * Support-matrix claims still require WS6 benchmark evidence; a working A2 demo is not a launch
 * claim. [deviceTier] is accepted for interface parity and telemetry but does not change behavior.
 */
class M2m100MtProvider(
    private val spec: MtModelSpec.Seq2SeqOnnx,
    private val modelDir: File,
) : TranslationProvider {

    override val providerId: String = "m2m100:${spec.modelId}"

    private sealed interface State {
        data object Uninitialized : State
        data class Ready(val engine: M2m100OnnxEngine) : State
        data object MissingModel : State
        data class Failed(val reason: String) : State
    }

    private var state: State = State.Uninitialized

    override fun translate(text: String, languagePair: String, deviceTier: String): TranslationResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TranslationResult(text = null, unsupportedReason = null)

        val direction = MtDirection.parse(languagePair)
            ?: return TranslationResult(
                text = null,
                unsupportedReason = "пара $languagePair не поддерживается этой моделью",
            )

        return when (val ready = ensureEngine()) {
            is State.Ready -> runTranslate(ready.engine, trimmed, direction)
            State.MissingModel -> TranslationResult(
                text = null,
                unsupportedReason = "MT-модель ${spec.modelId} не установлена",
            )
            is State.Failed -> TranslationResult(
                text = null,
                unsupportedReason = "MT-движок недоступен: ${ready.reason}",
            )
            State.Uninitialized -> TranslationResult(
                text = null,
                unsupportedReason = "MT-движок не инициализирован",
            )
        }
    }

    private fun runTranslate(engine: M2m100OnnxEngine, text: String, direction: MtDirection): TranslationResult =
        runCatching {
            val output = engine.translate(text, direction.source, direction.target)
            TranslationResult(text = output, unsupportedReason = null)
        }.getOrElse { t ->
            Log.e(TAG, "translate failed ${direction.source}->${direction.target}", t)
            TranslationResult(text = null, unsupportedReason = "ошибка перевода: ${t.message ?: t.javaClass.simpleName}")
        }

    /** Honest readiness without forcing a full session load when the model is simply absent. */
    fun isModelInstalled(): Boolean = spec.isInstalled(modelDir)

    private fun ensureEngine(): State {
        val current = state
        if (current !is State.Uninitialized) return current
        if (!spec.isInstalled(modelDir)) {
            state = State.MissingModel
            return state
        }
        val next = M2m100OnnxEngine.create(spec, modelDir)
            ?.let { State.Ready(it) }
            ?: State.Failed("session init returned null")
        state = next
        return next
    }

    override fun close() {
        (state as? State.Ready)?.engine?.close()
        state = State.Uninitialized
    }

    private companion object {
        const val TAG = "M2m100MtProvider"
    }
}

/** A parsed `"src->tgt"` translation direction restricted to the M2M-100 demo language codes. */
data class MtDirection(val source: String, val target: String) {
    companion object {
        private val SUPPORTED = setOf("ru", "en", "zh")

        /** Parse `"en->ru"`; null when malformed or outside the RU/EN/ZH demo scope. */
        fun parse(languagePair: String): MtDirection? {
            val parts = languagePair.split("->", "-", "_", limit = 2)
            if (parts.size != 2) return null
            val source = parts[0].trim().lowercase()
            val target = parts[1].trim().lowercase()
            if (source !in SUPPORTED || target !in SUPPORTED || source == target) return null
            return MtDirection(source, target)
        }
    }
}
