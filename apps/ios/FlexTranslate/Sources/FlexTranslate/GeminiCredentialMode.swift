import Foundation

/// How the app reaches Gemini for cloud MT translation.
///
/// backendMediation — the original path: translation goes through an operator-run
/// backend that holds the Gemini key server-side and returns text only. The app never
/// handles a Gemini credential; the backend issues short-lived ephemeral session tokens.
///
/// ownKey — BYOK ("bring your own key"): the user supplies their own Gemini API key,
/// stored in the iOS Keychain (never UserDefaults, never logged). The app POSTs directly
/// to the public Gemini REST endpoint. Works where Gemini is available; surfaces the
/// geo-restriction honestly when not.
///
/// Mirrors Android GeminiCredentialMode.
enum GeminiCredentialMode: String, Sendable {
    case backendMediation = "BACKEND_MEDIATION"
    case ownKey = "OWN_KEY"
}
