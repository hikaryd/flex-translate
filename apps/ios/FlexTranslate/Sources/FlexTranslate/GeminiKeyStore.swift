import Foundation
import Security

/// Secure storage seam for the user's Gemini API key (BYOK path).
///
/// Security contract:
/// - The key is NEVER stored in UserDefaults, files, or logs.
/// - saveKey / loadKey / clearKey are the ONLY authorized entry points.
/// - Nothing in this file logs or prints the key value; callers must honor the same constraint.
/// - loadKey returns nil when no key is saved.
///
/// Production implementation uses the iOS Keychain.
/// Mirrors Android GeminiKeyStore.
protocol GeminiKeyStore: Sendable {
    /// Encrypt and persist apiKey. Overwrites any previously stored key.
    func saveKey(_ apiKey: String)

    /// Retrieve the stored key, or nil if none has been saved.
    /// The returned value is sensitive — the caller must not log it.
    func loadKey() -> String?

    /// Erase the stored key. After this loadKey returns nil.
    func clearKey()

    /// True when a non-blank key is currently stored.
    func hasKey() -> Bool
}

extension GeminiKeyStore {
    func hasKey() -> Bool {
        loadKey().map { !$0.isEmpty } ?? false
    }
}

/// Production implementation backed by the iOS Keychain.
/// Uses kSecClassGenericPassword with AES-256-GCM encryption via the Secure Enclave.
final class KeychainGeminiKeyStore: GeminiKeyStore, @unchecked Sendable {

    private let service = "dev.flextranslate.gemini.byok"
    private let account = "gemini_api_key"

    func saveKey(_ apiKey: String) {
        guard let data = apiKey.data(using: .utf8) else { return }
        // Delete any existing entry first (update is cumbersome with Keychain).
        clearKey()
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecValueData: data,
            // kSecAttrAccessible: after first unlock, persisted across reboots
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

/// In-memory fake for unit tests — never touches the Keychain.
final class InMemoryGeminiKeyStore: GeminiKeyStore, @unchecked Sendable {
    private var storedKey: String?

    init(initialKey: String? = nil) {
        storedKey = initialKey
    }

    func saveKey(_ apiKey: String) { storedKey = apiKey }
    func loadKey() -> String? { storedKey }
    func clearKey() { storedKey = nil }
}
