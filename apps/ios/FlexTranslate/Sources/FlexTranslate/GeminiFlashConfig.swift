import Foundation

/// Config for the WS5 Gemini Flash cloud MT tier.
///
/// The concrete Gemini model id is config, never a literal, so it can change with no code edit.
///
/// Two modes are supported (see GeminiCredentialMode):
///
/// backendMediation (original path): translation goes through OUR backend, which injects the
/// Gemini credential server-side and returns translated text only. The app never holds a Gemini key.
///
/// ownKey (BYOK): the user supplies their own Gemini API key, stored in the iOS Keychain.
/// The app POSTs directly to the public Gemini REST endpoint. Geo-restriction surfaced honestly.
///
/// Mirrors Android GeminiFlashConfig.
struct GeminiFlashConfig: Sendable {
    let modelId: String
    let credentialMode: GeminiCredentialMode
    let backendBaseUrl: String
    let mediatedTranslatePath: String
    let thinkingLevel: String
    let streaming: Bool
    let timeoutSeconds: Double

    init(
        modelId: String = Self.defaultModelId,
        credentialMode: GeminiCredentialMode = .backendMediation,
        backendBaseUrl: String = "",
        mediatedTranslatePath: String = "/v1/cloud/translate",
        thinkingLevel: String = "low",
        streaming: Bool = false,
        timeoutSeconds: Double = 15
    ) {
        self.modelId = modelId
        self.credentialMode = credentialMode
        self.backendBaseUrl = backendBaseUrl
        self.mediatedTranslatePath = mediatedTranslatePath
        self.thinkingLevel = thinkingLevel
        self.streaming = streaming
        self.timeoutSeconds = timeoutSeconds
    }

    /// Current GA fast Gemini model. Upgradable without code change.
    static let defaultModelId = "gemini-3.5-flash"

    /// True only when a non-blank backend base URL is configured.
    var hasBackend: Bool { !backendBaseUrl.trimmingCharacters(in: .whitespaces).isEmpty }

    /// Absolute translate endpoint, or nil when no backend is configured.
    func translateEndpoint() -> String? {
        guard hasBackend else { return nil }
        let base = backendBaseUrl.hasSuffix("/")
            ? String(backendBaseUrl.dropLast())
            : backendBaseUrl
        let path = mediatedTranslatePath.hasPrefix("/")
            ? mediatedTranslatePath
            : "/\(mediatedTranslatePath)"
        return base + path
    }
}
