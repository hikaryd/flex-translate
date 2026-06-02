import Foundation
import SwiftUI

/// The interface language the whole UI chrome is rendered in. This is a RUNTIME app-level
/// choice (the in-app RU/EN toggle), independent of the device system locale and independent
/// of the source/target translation languages (FlexLanguage). Switching it flips LocalStrings
/// at the app root so every screen recomposes in the new language without losing state.
enum AppLanguage: String, CaseIterable, Sendable {
    case ru = "ru"
    case en = "en"

    var nativeLabel: String {
        switch self {
        case .ru: return "Русский"
        case .en: return "English"
        }
    }

    /// The sensible default for a fresh install: Russian when the device's primary locale
    /// is Russian, English otherwise. Used only when the user has not yet picked a language.
    static func fromSystem() -> AppLanguage {
        let code = Locale.current.language.languageCode?.identifier ?? ""
        return code.lowercased() == "ru" ? .ru : .en
    }

    static func fromCode(_ code: String?) -> AppLanguage {
        guard let code else { return fromSystem() }
        return AppLanguage(rawValue: code.lowercased()) ?? fromSystem()
    }
}

/// Persists the user's AppLanguage choice across launches via UserDefaults.
/// Until the user picks explicitly, load() falls back to AppLanguage.fromSystem().
/// No SwiftUI dependency here — the composition holds the live state; this only
/// reads/writes the durable value.
final class AppLanguageStore: @unchecked Sendable {
    static let shared = AppLanguageStore()

    private let key = "flex_app_language"

    private init() {}

    func load() -> AppLanguage {
        let stored = UserDefaults.standard.string(forKey: key)
        return AppLanguage.fromCode(stored)
    }

    func save(_ language: AppLanguage) {
        UserDefaults.standard.set(language.rawValue, forKey: key)
    }
}
