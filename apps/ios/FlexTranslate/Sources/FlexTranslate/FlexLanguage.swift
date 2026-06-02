import Foundation

/// Языки фазы 0 — RU/EN/ZH. Зеркалит Android FlexLanguage.
enum FlexLanguage: String, CaseIterable, Sendable, Equatable {
    case ru = "ru"
    case en = "en"
    case zh = "zh"

    var code: String { rawValue }

    var label: String {
        switch self {
        case .ru: return "Русский"
        case .en: return "English"
        case .zh: return "中文"
        }
    }

    var displayCode: String { rawValue.uppercased() }

    static func fromCode(_ code: String) -> FlexLanguage? {
        FlexLanguage(rawValue: code.lowercased())
    }
}
