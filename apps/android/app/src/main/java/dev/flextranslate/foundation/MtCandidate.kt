package dev.flextranslate.foundation

/** Где крутится MT-кандидат — от этого зависит бейдж offline/online и гейтинг. */
enum class MtExecution { ON_DEVICE, CLOUD }

/**
 * Как сессия выбирает MT-движок под каждый перевод.
 *
 * [AUTO] (по умолчанию): решаем в момент вызова — Gemini Flash, если облачный гейт пропустил
 * (online + согласие + есть credential), иначе модель на устройстве. Offline-first: нет сети /
 * согласия / credential ⇒ on-device, без молчаливого ухода в облако.
 *
 * [ON_DEVICE]: всегда выбранный on-device кандидат (M2M-100 / MiLMMT), даже если облачный гейт
 * пропустил бы.
 *
 * [CLOUD]: всегда пробуем Gemini Flash. Если гейт заблокировал — честно вернём причину, а не
 * молча свалимся на on-device.
 */
enum class MtRoutingMode {
    AUTO,
    ON_DEVICE,
    CLOUD,
}

/** Грубые градации качества/скорости для пикера, чтобы юзер мог осознанно выбирать компромисс. */
enum class MtRating(val label: String) {
    LOW("низкое"),
    MEDIUM("среднее"),
    HIGH("высокое"),
    HIGHEST("максимальное"),
}

/**
 * Модель машинного перевода, которую можно выбрать в пикере (требование «несколько моделей по
 * качеству/скорости, юзер выбирает сам»). По форме повторяет реестр [AsrCandidate].
 *
 * Правила честности:
 *  - [modelId] связывает [MtExecution.ON_DEVICE] кандидата с его [MtModelSpec], чтобы рантайм мог
 *    подхватить выбор юзера.
 *  - [MtExecution.CLOUD] кандидаты идут через WS5 backend-mediation (без встроенных ключей);
 *    облачный выбор честно гейтится на согласии/раскрытии/сети/бэкенде (без выдуманного вывода,
 *    без молчаливого отката на on-device).
 *  - `support` остаётся `not_claimed`; заявки в support-matrix требуют бенчмарк-доказательств WS6.
 */
data class MtCandidate(
    val id: String,
    val displayName: String,
    val execution: MtExecution,
    val quality: MtRating,
    val speed: MtRating,
    val approxSizeLabel: String,
    val languagePairs: List<String>,
    val notes: String,
    val modelId: String? = null,
    val support: String = "not_claimed",
    /**
     * Для on-device моделей с лицензионными ограничениями (например, MiLMMT на базе Gemma) — ссылка
     * на условия, которые приложение обязано показать юзеру, и короткое уведомление при показе/выборе
     * пака. Null для моделей со свободной лицензией, где показывать нечего.
     */
    val licenseTermsUrl: String? = null,
    val licenseNotice: String? = null,
) {
    val isDefault: Boolean get() = id == MtCandidateRegistry.DEFAULT_ID
}

object MtCandidateRegistry {
    const val DEFAULT_ID = "m2m100-418m"

    /** Gemma Terms of Use — лицензия, под которой идёт MiLMMT (на базе Gemma); показываем юзеру. */
    const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"

    /** Демо-набор из четырёх направлений с пивотом через RU — общий для on-device кандидатов. */
    private val DEMO_PAIRS = listOf("ru->en", "en->ru", "ru->zh", "zh->ru")

    val candidates: List<MtCandidate> = listOf(
        MtCandidate(
            id = DEFAULT_ID,
            displayName = "M2M-100 418M (на устройстве)",
            execution = MtExecution.ON_DEVICE,
            quality = MtRating.MEDIUM,
            speed = MtRating.MEDIUM,
            approxSizeLabel = "≈600 MB",
            languagePairs = DEMO_PAIRS,
            notes = "Сбалансированная офлайн-модель. Прямой перевод RU↔EN и RU↔ZH без англ. пивота. MIT.",
            modelId = "m2m100-418m",
        ),
        MtCandidate(
            id = "milmmt-46-4b-q6",
            displayName = "MiLMMT-46 4B (качество, на устройстве)",
            execution = MtExecution.ON_DEVICE,
            quality = MtRating.HIGH,
            speed = MtRating.LOW,
            approxSizeLabel = "≈3.74 GB",
            languagePairs = DEMO_PAIRS,
            notes = "Качественный офлайн-перевод на базе LLM (Gemma-3 4B, Q6 GGUF, llama.cpp). " +
                "Заметно медленнее (секунды), но выше качество. Лицензия Gemma — условия показаны при выборе.",
            modelId = "milmmt-46-4b-q6",
            licenseTermsUrl = GEMMA_TERMS_URL,
            licenseNotice = "Модель основана на Google Gemma. Используя её, вы принимаете Gemma Terms of Use " +
                "и Gemma Prohibited Use Policy.",
        ),
        MtCandidate(
            id = "opus-mt-fast",
            displayName = "Opus-MT (быстрая, на устройстве)",
            execution = MtExecution.ON_DEVICE,
            quality = MtRating.LOW,
            speed = MtRating.HIGH,
            approxSizeLabel = "≈300 MB / пара",
            languagePairs = listOf("ru->en", "en->ru"),
            notes = "Лёгкие двунаправленные модели по парам (Helsinki-NLP). Быстрее, но по одной модели на направление. Опционально.",
            modelId = null,
        ),
        MtCandidate(
            id = "gemini-flash-cloud",
            displayName = "Gemini Flash (облако)",
            execution = MtExecution.CLOUD,
            quality = MtRating.HIGHEST,
            speed = MtRating.HIGH,
            approxSizeLabel = "—",
            languagePairs = DEMO_PAIRS,
            notes = "Наивысшее качество через облако (Gemini Flash, backend-mediation, без встроенных " +
                "ключей). Требует согласия, раскрытия, сети и backend-endpoint (см. Облако); иначе — " +
                "честная блокировка, без подмены офлайн-моделью.",
            modelId = null,
        ),
    )

    val default: MtCandidate = candidates.first { it.isDefault }

    fun byId(id: String): MtCandidate? = candidates.firstOrNull { it.id == id }
}
