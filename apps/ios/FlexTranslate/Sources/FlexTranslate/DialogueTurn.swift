import Foundation

/// One finalized utterance in a two-way dialogue session.
///
/// Immutable except for translatedText and translationReason which transition from nil/pending
/// to a real value once the MT worker completes. State updates always produce a new copy via
/// withTranslation() — never mutate in place.
///
/// - id:                  Stable unique id for SwiftUI ForEach keying (UUID string).
/// - monotonicTs:         Monotonic timestamp in ms at utterance finalization — used for ordering.
/// - spokenLanguage:      The FlexLanguage the speaker used for this utterance.
/// - originalText:        The genuine ASR output (never fabricated).
/// - translatedText:      Real MT output once available; nil while pending or when gated.
/// - translationReason:   Honest gating reason when translation is blocked; nil when translation
///                        succeeded or is still pending (not yet attempted).
/// - translationLanguage: The FlexLanguage that translatedText is expressed in
///                        (the counterpart language at the time of utterance).
/// - mtEngineUsed:        Human-readable label of the engine that produced translatedText.
///                        Nil while the turn is still pending.
struct DialogueTurn: Identifiable, Equatable, Sendable {
    let id: String
    let monotonicTs: Int64
    let spokenLanguage: FlexLanguage
    let originalText: String
    let translatedText: String?
    let translationReason: String?
    let translationLanguage: FlexLanguage
    let mtEngineUsed: String?

    init(
        id: String = UUID().uuidString,
        monotonicTs: Int64,
        spokenLanguage: FlexLanguage,
        originalText: String,
        translatedText: String? = nil,
        translationReason: String? = nil,
        translationLanguage: FlexLanguage,
        mtEngineUsed: String? = nil
    ) {
        self.id = id
        self.monotonicTs = monotonicTs
        self.spokenLanguage = spokenLanguage
        self.originalText = originalText
        self.translatedText = translatedText
        self.translationReason = translationReason
        self.translationLanguage = translationLanguage
        self.mtEngineUsed = mtEngineUsed
    }

    /// True while the MT worker has not yet resolved this turn's translation.
    var translationPending: Bool {
        translatedText == nil && translationReason == nil
    }

    /// Return a new copy of this turn with the translation result applied.
    /// Exactly one of text and reason should be non-nil (mirrors TranslationResult).
    func withTranslation(text: String?, reason: String?, engineLabel: String? = nil) -> DialogueTurn {
        DialogueTurn(
            id: id,
            monotonicTs: monotonicTs,
            spokenLanguage: spokenLanguage,
            originalText: originalText,
            translatedText: text,
            translationReason: reason,
            translationLanguage: translationLanguage,
            mtEngineUsed: engineLabel
        )
    }
}
