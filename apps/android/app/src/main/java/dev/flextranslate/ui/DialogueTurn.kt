package dev.flextranslate.ui

/**
 * One finalized utterance in a two-way dialogue session.
 *
 * Immutable except for [translatedText] and [translationReason] which transition from null/pending
 * to a real value once the MT worker completes. State updates always produce a new copy via
 * [withTranslation] — never mutate in place.
 *
 * @param id           Stable unique id for Compose keying (UUID string).
 * @param monotonicTs  Monotonic timestamp in ms at utterance finalization — used for ordering.
 * @param spokenLanguage  The [FlexLanguage] the speaker used for this utterance.
 * @param originalText    The genuine ASR output (never fabricated).
 * @param translatedText  Real MT output once available; null while pending or when gated.
 * @param translationReason Honest gating reason when translation is blocked; null when translation
 *   succeeded or is still pending (not yet attempted).
 * @param translationLanguage The [FlexLanguage] that [translatedText] is expressed in
 *   (the counterpart language at the time of utterance).
 * @param mtEngineUsed Human-readable label of the engine that produced [translatedText], e.g.
 *   "Gemini Flash" or the on-device model id. Null while the turn is still pending.
 */
data class DialogueTurn(
    val id: String,
    val monotonicTs: Long,
    val spokenLanguage: FlexLanguage,
    val originalText: String,
    val translatedText: String?,
    val translationReason: String?,
    val translationLanguage: FlexLanguage,
    val mtEngineUsed: String? = null,
) {
    /** True while the MT worker has not yet resolved this turn's translation. */
    val translationPending: Boolean
        get() = translatedText == null && translationReason == null

    /**
     * Return a new copy of this turn with the translation result applied.
     * Exactly one of [text] and [reason] should be non-null (mirrors [TranslationResult]).
     * [engineLabel] is the human-readable engine name (e.g. "Gemini Flash" or the on-device
     * model id) that produced [text]; null when the translation was blocked/failed.
     */
    fun withTranslation(text: String?, reason: String?, engineLabel: String? = null): DialogueTurn =
        copy(translatedText = text, translationReason = reason, mtEngineUsed = engineLabel)
}
