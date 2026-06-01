package dev.flextranslate.foundation

import android.util.Log
import java.io.File

/**
 * Real on-device machine translation via MiLMMT-46-4B (Gemma-3 architecture) Q6_K GGUF, run on the
 * vendored llama.cpp ([LlamaCppBridge]) implementing [TranslationProvider]. This is the WS4 QUALITY
 * tier the user can pick versus the M2M-100 balanced tier and the cloud option.
 *
 * Honesty (G005/WS4 A2): every non-null [TranslationResult.text] is GENUINE model output decoded by
 * llama.cpp. Nothing is fabricated — a missing model, an unavailable native runtime, or a decode
 * failure surfaces an honest [TranslationResult.unsupportedReason] (gated), never fake text.
 *
 * The model + context are created lazily on first use and reused across calls (loading a 4B GGUF is
 * expensive — seconds). A 4B LLM on mobile is slow (seconds per translation); that latency is
 * acceptable for the quality tier and is a benchmark/gate concern (WS6), not a correctness one. The
 * heavy load/generate runs off the main thread — the caller ([LiveSessionState.translateFinal])
 * already invokes [translate] on a worker thread.
 *
 * Prompt format comes from the model card (xiaomi-research/MiLMMT-46-4B-v0.1), a completion-style
 * MT instruction naming full source/target language names:
 *   `Translate this from <Source> to <Target>:\n<Source>: <text>\n<Target>:`
 */
class MilmmtMtProvider(
    private val spec: MtModelSpec.Gguf,
    private val modelDir: File,
    private val bridge: LlamaCppBridge = LlamaCppBridge,
) : TranslationProvider {

    override val providerId: String = "milmmt:${spec.modelId}"

    private sealed interface State {
        data object Uninitialized : State
        data class Ready(val handle: Long) : State
        data object MissingModel : State
        data object RuntimeUnavailable : State
        data class Failed(val reason: String) : State
    }

    private var state: State = State.Uninitialized

    override fun translate(text: String, languagePair: String, deviceTier: String): TranslationResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TranslationResult(text = null, unsupportedReason = null)

        val direction = MilmmtDirection.parse(languagePair)
            ?: return TranslationResult(
                text = null,
                unsupportedReason = "пара $languagePair не поддерживается этой моделью",
            )

        return when (val ready = ensureModel()) {
            is State.Ready -> runTranslate(ready.handle, trimmed, direction)
            State.MissingModel -> TranslationResult(
                text = null,
                unsupportedReason = "MT-модель ${spec.modelId} не установлена",
            )
            State.RuntimeUnavailable -> TranslationResult(
                text = null,
                unsupportedReason = "MiLMMT runtime ещё не установлен",
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

    private fun runTranslate(handle: Long, text: String, direction: MilmmtDirection): TranslationResult {
        val prompt = buildPrompt(text, direction)
        val output = bridge.generate(handle, prompt, MAX_NEW_TOKENS)
            ?: return TranslationResult(
                text = null,
                unsupportedReason = "ошибка генерации llama.cpp",
            )
        val cleaned = output.trim()
        if (cleaned.isEmpty()) {
            return TranslationResult(text = null, unsupportedReason = "пустой ответ модели")
        }
        return TranslationResult(text = cleaned, unsupportedReason = null)
    }

    /** Completion-style MT prompt from the MiLMMT model card; full English language names. */
    private fun buildPrompt(text: String, direction: MilmmtDirection): String =
        "Translate this from ${direction.sourceName} to ${direction.targetName}:\n" +
            "${direction.sourceName}: $text\n" +
            "${direction.targetName}:"

    /** Honest readiness without forcing a full model load when the file is simply absent. */
    fun isModelInstalled(): Boolean = spec.isInstalled(modelDir)

    private fun ensureModel(): State {
        val current = state
        if (current !is State.Uninitialized) return current
        if (!bridge.isAvailable) {
            state = State.RuntimeUnavailable
            return state
        }
        if (!spec.isInstalled(modelDir)) {
            state = State.MissingModel
            return state
        }
        val ggufPath = File(modelDir, spec.gguf).absolutePath
        val handle = bridge.load(ggufPath, DEFAULT_THREADS, DEFAULT_CTX)
        state = if (handle != 0L) {
            Log.i(TAG, "MiLMMT loaded: $ggufPath")
            State.Ready(handle)
        } else {
            State.Failed("llama.cpp model load returned 0")
        }
        return state
    }

    override fun close() {
        (state as? State.Ready)?.let { bridge.free(it.handle) }
        state = State.Uninitialized
    }

    private companion object {
        const val TAG = "MilmmtMtProvider"
        // 4B on mobile: keep the context modest (short conversational utterances) for memory/speed.
        const val DEFAULT_CTX = 1024
        const val DEFAULT_THREADS = 4
        const val MAX_NEW_TOKENS = 128
    }
}

/**
 * A parsed `"src->tgt"` direction restricted to the demo language codes, carrying the full English
 * language names MiLMMT's prompt requires (e.g. `"zh"` → `"Chinese (Simplified)"`).
 */
data class MilmmtDirection(val source: String, val target: String, val sourceName: String, val targetName: String) {
    companion object {
        private val LANGUAGE_NAMES = mapOf(
            "ru" to "Russian",
            "en" to "English",
            "zh" to "Chinese (Simplified)",
        )

        /** Parse `"en->ru"`; null when malformed or outside the RU/EN/ZH demo scope. */
        fun parse(languagePair: String): MilmmtDirection? {
            val parts = languagePair.split("->", "-", "_", limit = 2)
            if (parts.size != 2) return null
            val source = parts[0].trim().lowercase()
            val target = parts[1].trim().lowercase()
            val sourceName = LANGUAGE_NAMES[source] ?: return null
            val targetName = LANGUAGE_NAMES[target] ?: return null
            if (source == target) return null
            return MilmmtDirection(source, target, sourceName, targetName)
        }
    }
}
