import Foundation
import SwiftUI

/// Язык интерфейса всего приложения. Это выбор уровня приложения в рантайме (тумблер RU/EN
/// внутри приложения), не связанный ни с системной локалью устройства, ни с языками
/// перевода (FlexLanguage). Переключение меняет LocalStrings в корне приложения — все экраны
/// пересобираются на новом языке, не теряя состояния.
enum AppLanguage: String, CaseIterable, Sendable {
    case ru = "ru"
    case en = "en"

    var nativeLabel: String {
        switch self {
        case .ru: return "Русский"
        case .en: return "English"
        }
    }

    /// Разумный дефолт при чистой установке: русский, если основная локаль устройства русская,
    /// иначе английский. Нужен только пока пользователь сам язык не выбрал.
    static func fromSystem() -> AppLanguage {
        let code = Locale.current.language.languageCode?.identifier ?? ""
        return code.lowercased() == "ru" ? .ru : .en
    }

    static func fromCode(_ code: String?) -> AppLanguage {
        guard let code else { return fromSystem() }
        return AppLanguage(rawValue: code.lowercased()) ?? fromSystem()
    }
}

/// Хранит выбранный язык между запусками через UserDefaults.
/// Пока пользователь не выбрал явно, load() откатывается к AppLanguage.fromSystem().
/// На SwiftUI тут не завязано — живое состояние держит композиция, а это только
/// читает/пишет сохранённое значение.
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
