import Foundation

/// The hard gate every cloud MT call must pass BEFORE any network traffic.
///
/// Wraps the CloudOptInState preconditions — explicit user consent, accepted disclosure,
/// and online network — and turns a failure into a product-language Decision.blocked reason.
///
/// Two credential modes are supported (see GeminiCredentialMode):
///
/// backendMediation: also requires a configured backend endpoint and a live
/// backend-issued ephemeral token. The Gemini key never leaves the server.
///
/// ownKey (BYOK): skips backend/token checks — the user's own encrypted Keychain key is
/// supplied by the caller. Still requires consent, disclosure, and an online network.
/// The key is NEVER inspected, stored, or logged here.
///
/// Mirrors Android CloudCallGate.
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
            // Synthesise a dummy credential so the sealed type stays unified.
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

    // MARK: - Honest gate reasons (mirror Android constants)

    static let reasonNoBackend = "No backend endpoint configured (Cloud)"
    static let reasonDisabled = "Cloud is disabled"
    static let reasonNoConsent = "Cloud is disabled — no consent"
    static let reasonNoDisclosure = "Disclosure acceptance required"
    static let reasonOffline = "No network — cloud translation unavailable"
    static let reasonNoToken = "Backend token required"
    static let reasonNoOwnKey = "Gemini API key not set"
}
