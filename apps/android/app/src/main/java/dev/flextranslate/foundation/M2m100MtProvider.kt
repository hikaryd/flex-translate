package dev.flextranslate.foundation

import android.util.Log
import java.io.File

/**
 * Настоящий on-device машинный перевод через M2M-100 ([M2m100OnnxEngine]), реализует
 * [TranslationProvider].
 *
 * G005/WS4 (A2): каждый ненулевой [TranslationResult.text] — ПОДЛИННЫЙ вывод модели. Никаких
 * выдуманных/заготовленных переводов нет: если файлов модели нет или перевод упал, провайдер
 * честно вернёт [TranslationResult.unsupportedReason] (гейт), но не фейковый текст.
 *
 * Движок создаётся лениво при первом обращении и переиспользуется (грузить две ONNX-сессии дорого).
 * Направление берём из строки [languagePair] вида `"src->tgt"`, например `"en->ru"`. M2M-100 тянет
 * RU↔EN и RU↔ZH напрямую через форсированный target-токен — без пивота через английский.
 *
 * Заявления в support-matrix всё ещё требуют бенчмарков WS6; рабочая демка A2 — не заявка на релиз.
 * [deviceTier] принимаем ради единообразия интерфейса и телеметрии, на поведение он не влияет.
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

    /** Честная готовность без полной загрузки сессии, когда модели попросту нет. */
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

/** Распарсенное направление перевода `"src->tgt"`, ограниченное демо-кодами языков M2M-100. */
data class MtDirection(val source: String, val target: String) {
    companion object {
        private val SUPPORTED = setOf("ru", "en", "zh")

        /** Парсит `"en->ru"`; null, если строка кривая или выходит за демо-набор RU/EN/ZH. */
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
