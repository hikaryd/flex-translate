import Foundation
import Security

/// Граница безопасного хранения личного ключа Gemini (BYOK-путь).
///
/// Контракт безопасности:
/// - Ключ НИКОГДА не попадает в UserDefaults, файлы или логи.
/// - saveKey / loadKey / clearKey — ЕДИНСТВЕННЫЕ разрешённые точки входа.
/// - Здесь ничего не логирует и не печатает значение ключа; вызывающий код должен держать ту же планку.
/// - loadKey отдаёт nil, когда ключ не сохранён.
///
/// В проде используется iOS Keychain.
/// Калька с Android GeminiKeyStore.
protocol GeminiKeyStore: Sendable {
    /// Шифрует и сохраняет apiKey. Перезаписывает ранее сохранённый ключ.
    func saveKey(_ apiKey: String)

    /// Достаёт сохранённый ключ или nil, если ничего не сохранено.
    /// Возвращаемое значение чувствительное — вызывающий код не должен его логировать.
    func loadKey() -> String?

    /// Стирает сохранённый ключ. После этого loadKey отдаёт nil.
    func clearKey()

    /// true, когда сейчас хранится непустой ключ.
    func hasKey() -> Bool
}

extension GeminiKeyStore {
    func hasKey() -> Bool {
        loadKey().map { !$0.isEmpty } ?? false
    }
}

/// Прод-реализация поверх iOS Keychain.
/// Использует kSecClassGenericPassword с шифрованием AES-256-GCM через Secure Enclave.
final class KeychainGeminiKeyStore: GeminiKeyStore, @unchecked Sendable {

    private let service = "dev.flextranslate.gemini.byok"
    private let account = "gemini_api_key"

    func saveKey(_ apiKey: String) {
        guard let data = apiKey.data(using: .utf8) else { return }
        // Сначала удаляем старую запись — обновлять через Keychain неудобно.
        clearKey()
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecValueData: data,
            // kSecAttrAccessible: доступно после первой разблокировки, переживает перезагрузки
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    func loadKey() -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func clearKey() {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// In-memory фейк для юнит-тестов — Keychain не трогает.
final class InMemoryGeminiKeyStore: GeminiKeyStore, @unchecked Sendable {
    private var storedKey: String?

    init(initialKey: String? = nil) {
        storedKey = initialKey
    }

    func saveKey(_ apiKey: String) { storedKey = apiKey }
    func loadKey() -> String? { storedKey }
    func clearKey() { storedKey = nil }
}
