package dev.flextranslate.foundation

import android.util.Log
import java.io.File

/**
 * Реальный локальный перевод через MiLMMT-46-4B (архитектура Gemma-3), Q6_K GGUF, поверх вендоренного
 * llama.cpp ([LlamaCppBridge]). Реализует [TranslationProvider]. Это QUALITY-тир WS4 — пользователь
 * выбирает его против сбалансированного M2M-100 и облака.
 *
 * Честность (G005/WS4 A2): любой не-null [TranslationResult.text] — НАСТОЯЩИЙ вывод модели, раскодированный
 * llama.cpp. Ничего не выдумываем: нет модели, недоступен нативный runtime, упал decode — отдаём честную
 * [TranslationResult.unsupportedReason] (гейт), а не фейковый текст.
 *
 * Модель и контекст создаём лениво при первом вызове и переиспользуем — загрузка 4B GGUF дорогая (секунды).
 * 4B LLM на мобиле медленный (секунды на перевод); для quality-тира это нормально и относится к бенчмарку/гейту
 * (WS6), а не к корректности. Тяжёлая загрузка/генерация идёт не в главном потоке — вызывающий
 * ([LiveSessionState.translateFinal]) уже дёргает [translate] на воркер-потоке.
 *
 * Формат промпта — из карточки модели (xiaomi-research/MiLMMT-46-4B-v0.1): completion-style инструкция
 * с полными названиями языков источника и цели:
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

    /** Completion-style промпт из карточки MiLMMT; полные английские названия языков. */
    private fun buildPrompt(text: String, direction: MilmmtDirection): String =
        "Translate this from ${direction.sourceName} to ${direction.targetName}:\n" +
            "${direction.sourceName}: $text\n" +
            "${direction.targetName}:"

    /** Честная проверка готовности без полной загрузки модели — когда файла просто нет. */
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
        // 4B на мобиле: держим контекст скромным (реплики в диалоге короткие) ради памяти и скорости.
        const val DEFAULT_CTX = 1024
        const val DEFAULT_THREADS = 4
        const val MAX_NEW_TOKENS = 128
    }
}

/**
 * Разобранное направление `"src->tgt"` в пределах демо-набора языков. Несёт полные английские названия,
 * которые требует промпт MiLMMT (напр. `"zh"` → `"Chinese (Simplified)"`).
 */
data class MilmmtDirection(val source: String, val target: String, val sourceName: String, val targetName: String) {
    companion object {
        private val LANGUAGE_NAMES = mapOf(
            "ru" to "Russian",
            "en" to "English",
            "zh" to "Chinese (Simplified)",
        )

        /** Разбирает `"en->ru"`; null если формат битый или язык вне демо-набора RU/EN/ZH. */
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
