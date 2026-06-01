package dev.flextranslate.foundation

/** Where an MT candidate runs — drives the offline/online badge and gating. */
enum class MtExecution { ON_DEVICE, CLOUD }

/** Coarse quality/speed bands the picker surfaces so the user can trade off by intent. */
enum class MtRating(val label: String) {
    LOW("низкое"),
    MEDIUM("среднее"),
    HIGH("высокое"),
    HIGHEST("максимальное"),
}

/**
 * A selectable machine-translation model for the picker (the "several models by quality/speed,
 * user chooses" requirement). Mirrors the [AsrCandidate] registry shape.
 *
 * Honesty rules:
 *  - [modelId] links an [MtExecution.ON_DEVICE] candidate to its [MtModelSpec] so the runtime can
 *    use the user's selection.
 *  - [MtExecution.CLOUD] candidates run via WS5 backend-mediation (no embedded keys); a cloud
 *    selection gates honestly on consent/disclosure/network/backend (no fabricated output, no silent
 *    fallback to an on-device model).
 *  - `support` stays `not_claimed`; support-matrix claims need WS6 benchmark evidence.
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
     * For license-restricted on-device models (e.g. Gemma-derived MiLMMT), the URL to the terms the
     * app must pass through to the user, and a short notice surfaced when the pack is shown/selected.
     * Null for permissively-licensed models that need no pass-through.
     */
    val licenseTermsUrl: String? = null,
    val licenseNotice: String? = null,
) {
    val isDefault: Boolean get() = id == MtCandidateRegistry.DEFAULT_ID
}

object MtCandidateRegistry {
    const val DEFAULT_ID = "m2m100-418m"

    /** Gemma Terms of Use — the license MiLMMT (Gemma-derived) carries; passed through to the user. */
    const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"

    /** Four-direction RU-pivot demo scope shared by the on-device candidates. */
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
