import Foundation

/// The real WS5 cloud MT tier behind the gemini-flash-cloud picker candidate.
///
/// Supports two credential modes (see GeminiCredentialMode):
///
/// backendMediation (original path): translation goes through OUR mediation backend, which
/// injects the Gemini credential server-side and returns text only. The app never holds a
/// Gemini key. Gated by CloudCallGate (consent + disclosure + online + ephemeral token +
/// backend configured).
///
/// ownKey (BYOK): the user's own Gemini API key (stored in iOS Keychain, never UserDefaults)
/// is fetched just-in-time and sent in x-goog-api-key directly to the Gemini REST endpoint
/// via GeminiDirectClient. Still gated (consent + disclosure + online + key present).
/// Geo-restriction (HTTP 400 "User location is not supported") is surfaced honestly.
///
/// Honesty contract: a gated, offline, declined, or failed call returns
/// TranslationResult(text: nil, unsupportedReason: <reason>). Never fabricates output,
/// never silently falls back to an on-device model.
///
/// Model id is config-driven (GeminiFlashConfig.modelId, default gemini-3.5-flash).
///
/// Mirrors Android GeminiFlashTranslationProvider.
final class GeminiFlashTranslationProvider: TranslationProvider, @unchecked Sendable {

    static let providerId = "gemini-flash-cloud"

    // Honest reasons for the ownKey direct path.
    static let reasonGeoBlocked =
        "Gemini is not available in your region — use backend mode or a VPN"
    static let reasonKeyRejected =
        "Gemini API key rejected (check the key in Cloud settings)"
    static let reasonDirectFailed =
        "Direct Gemini call failed"

    let providerId: String = GeminiFlashTranslationProvider.providerId

    private let config: GeminiFlashConfig
    private let gate: CloudCallGate
    private let backend: any CloudMediationClient
    private let directClient: GeminiDirectClient
    private let keyStore: (any GeminiKeyStore)?
    private let clock: @Sendable () -> Int64

    init(
        config: GeminiFlashConfig,
        gate: CloudCallGate,
        backend: any CloudMediationClient,
        directClient: GeminiDirectClient? = nil,
        keyStore: (any GeminiKeyStore)? = nil,
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.config = config
        self.gate = gate
        self.backend = backend
        self.directClient = directClient ?? GeminiDirectClient(config: config)
        self.keyStore = keyStore
        self.clock = clock
    }

    func translate(text: String, languagePair: String, deviceTier: String) -> TranslationResult {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return TranslationResult(text: nil, unsupportedReason: nil) }

        // Restrict to advertised RU-pivot demo directions (uses existing MtDirection from M2m100MtProvider).
        guard MtDirection.parse(languagePair) != nil else {
            return TranslationResult(
                text: nil,
                unsupportedReason: "pair \(languagePair) not supported by cloud model"
            )
        }

        // Hard gate: no consent / no disclosure / offline → no call (both modes).
        let decision = gate.evaluate(providerId: Self.providerId, nowEpochMs: clock())
        switch decision {
        case .blocked(let reason):
            return TranslationResult(text: nil, unsupportedReason: reason)
        case .allowed(let credential):
            switch config.credentialMode {
            case .ownKey:
                return translateDirect(trimmed, languagePair: languagePair)
            case .backendMediation:
                return translateMediated(trimmed, languagePair: languagePair, credential: credential)
            }
        }
    }

    /// ownKey path: fetch key from Keychain just-in-time, call Gemini directly.
    private func translateDirect(_ text: String, languagePair: String) -> TranslationResult {
        guard let apiKey = keyStore?.loadKey(), !apiKey.isEmpty else {
            return TranslationResult(text: nil, unsupportedReason: CloudCallGate.reasonNoOwnKey)
        }
        switch directClient.translate(text: text, languagePair: languagePair, apiKey: apiKey) {
        case .ok(let translated):
            return TranslationResult(text: translated, unsupportedReason: nil)
        case .geoBlocked:
            return TranslationResult(text: nil, unsupportedReason: Self.reasonGeoBlocked)
        case .keyRejected:
            return TranslationResult(text: nil, unsupportedReason: Self.reasonKeyRejected)
        case .failed:
            return TranslationResult(text: nil, unsupportedReason: Self.reasonDirectFailed)
        }
    }

    /// backendMediation path: build intent request and delegate to the mediation backend.
    private func translateMediated(
        _ text: String,
        languagePair: String,
        credential: CloudCredential
    ) -> TranslationResult {
        let request = GeminiTranslateRequest(
            providerId: Self.providerId,
            modelId: config.modelId,
            languagePair: languagePair,
            text: text,
            thinkingLevel: config.thinkingLevel,
            stream: config.streaming
        )
        switch backend.translate(request: request, credential: credential) {
        case .ok(let translated, _):
            return TranslationResult(text: translated, unsupportedReason: nil)
        case .refused(let reason):
            return TranslationResult(text: nil, unsupportedReason: reason)
        case .failed:
            return TranslationResult(text: nil, unsupportedReason: "Cloud translation unavailable")
        }
    }
}

