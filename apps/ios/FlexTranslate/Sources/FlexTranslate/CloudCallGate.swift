import Foundation

/// Жёсткий гейт, который любой облачный MT-вызов обязан пройти ДО любого сетевого трафика.
///
/// Оборачивает предусловия CloudOptInState — явное согласие пользователя, принятый disclosure
/// и онлайн-сеть — и превращает провал в продуктовую формулировку Decision.blocked.
///
/// Поддерживаем два режима учётных данных (см. GeminiCredentialMode):
///
/// backendMediation: дополнительно нужен настроенный backend-endpoint и живой
/// эфемерный токен, выданный бэкендом. Ключ Gemini не покидает сервер.
///
/// ownKey (BYOK): пропускает проверки backend/токена — собственный зашифрованный ключ из Keychain
/// передаёт вызывающая сторона. Согласие, disclosure и онлайн-сеть всё равно обязательны.
/// Ключ здесь НИКОГДА не читаем, не храним и не логируем.
///
/// Зеркалит Android CloudCallGate.
final class CloudCallGate: @unchecked Sendable {

    private let stateProvider: @Sendable (String) -> CloudOptInState?
    private let config: GeminiFlashConfig
    private let keyStore: (any GeminiKeyStore)?

    init(
        stateProvider: @escaping @Sendable (String) -> CloudOptInState?,
        config: GeminiFlashConfig,
        keyStore: (any GeminiKeyStore)? = nil
    ) {
        self.stateProvider = stateProvider
        self.config = config
        self.keyStore = keyStore
    }

    enum Decision: Sendable {
        case allowed(CloudCredential)
        case blocked(String)
    }

    func evaluate(providerId: String, nowEpochMs: Int64) -> Decision {
        guard let state = stateProvider(providerId) else {
            return .blocked(Self.reasonDisabled)
        }
        guard state.userConsented else { return .blocked(Self.reasonNoConsent) }
        guard state.disclosureAccepted else { return .blocked(Self.reasonNoDisclosure) }
        guard state.networkState == "online" else { return .blocked(Self.reasonOffline) }

        switch config.credentialMode {
        case .ownKey:
            guard keyStore?.hasKey() == true else { return .blocked(Self.reasonNoOwnKey) }
            // Подсовываем фиктивный credential, чтобы тип остался единым.
            return .allowed(CloudCredential(source: "own_key", expiresAtEpochMs: Int64.max))

        case .backendMediation:
            guard config.hasBackend else { return .blocked(Self.reasonNoBackend) }
            guard let credential = state.credential,
                  credential.isEphemeral(nowEpochMs: nowEpochMs) else {
                return .blocked(Self.reasonNoToken)
            }
            return .allowed(credential)
        }
    }

    // MARK: - Честные причины блокировки (зеркалят Android-константы)

    static let reasonNoBackend = "No backend endpoint configured (Cloud)"
    static let reasonDisabled = "Cloud is disabled"
    static let reasonNoConsent = "Cloud is disabled — no consent"
    static let reasonNoDisclosure = "Disclosure acceptance required"
    static let reasonOffline = "No network — cloud translation unavailable"
    static let reasonNoToken = "Backend token required"
    static let reasonNoOwnKey = "Gemini API key not set"
}
