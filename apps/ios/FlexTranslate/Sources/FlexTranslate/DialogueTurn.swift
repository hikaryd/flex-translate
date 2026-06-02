import Foundation

/// Одна завершённая реплика в двустороннем диалоге.
///
/// Иммутабельна, кроме translatedText и translationReason: они переходят из nil/pending
/// в реальное значение, когда MT-воркер отработает. Любое обновление делает новую копию
/// через withTranslation() — на месте ничего не правим.
///
/// - id:                  стабильный id для ForEach в SwiftUI (строка UUID).
/// - monotonicTs:         монотонная метка времени (мс) в момент финализации — для порядка.
/// - spokenLanguage:      FlexLanguage, на котором говорил спикер.
/// - originalText:        настоящий вывод ASR (никогда не выдуманный).
/// - translatedText:      настоящий вывод MT, когда готов; nil пока pending или если перевод закрыт.
/// - translationReason:   честная причина, если перевод заблокирован; nil при успехе или
///                        пока перевод ещё не пробовали.
/// - translationLanguage: FlexLanguage, на котором выражен translatedText (язык собеседника
///                        на момент реплики).
/// - mtEngineUsed:        человекочитаемая метка движка, который дал translatedText.
///                        nil пока реплика в pending.
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

    /// true, пока MT-воркер ещё не разрешил перевод этой реплики.
    var translationPending: Bool {
        translatedText == nil && translationReason == nil
    }

    /// Новая копия реплики с применённым результатом перевода.
    /// Ненулевым должно быть ровно одно из text/reason (как в TranslationResult).
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
