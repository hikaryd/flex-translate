package dev.flextranslate.foundation

/**
 * How the app reaches Gemini for cloud MT translation.
 *
 * [BACKEND_MEDIATION] — the original path (WS5): translation goes through an operator-run
 * backend that holds the Gemini key server-side and returns text only. The app never handles
 * a Gemini credential; the backend issues short-lived ephemeral session tokens instead.
 *
 * [OWN_KEY] — BYOK ("bring your own key"): the user supplies their own Gemini API key, which
 * is stored encrypted (EncryptedSharedPreferences / AES-256-GCM + KeyStore). The app POSTs
 * directly to the public Gemini REST endpoint with the user's key in the `x-goog-api-key`
 * header. Works where Gemini is available; surfaces the geo-restriction honestly when not.
 * The key is NEVER logged, printed, or included in any error message.
 */
enum class GeminiCredentialMode {
    BACKEND_MEDIATION,
    OWN_KEY,
}
