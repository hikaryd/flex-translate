package dev.flextranslate.foundation

/**
 * Config for the WS5 Gemini Flash cloud MT tier (`gemini-flash-cloud`).
 *
 * Per `docs/design/ws5-gemini-flash.md` §4 the concrete Gemini model id is **config, never a
 * literal**, so "3.5 Flash" / the latest can change with no code edit.
 *
 * Two modes are now supported (see [GeminiCredentialMode]):
 *
 * **[GeminiCredentialMode.BACKEND_MEDIATION]** (original WS5 path): the endpoints here are OUR
 * backend's, not Google's — the app never holds Google's host + key together, and ships no Gemini
 * API key. The backend injects the real Gemini credential server-side and returns translated text.
 *
 * **[GeminiCredentialMode.OWN_KEY]** (BYOK): the user supplies their own Gemini API key, stored
 * encrypted on-device (EncryptedSharedPreferences). The app POSTs directly to the public Gemini
 * REST endpoint. Works where Gemini is not geo-blocked; surfaces the geo-restriction honestly
 * when the region is unsupported (HTTP 400 "User location is not supported").
 *
 * @property modelId Gemini model id. Default is `gemini-3.5-flash` (GA 2026-05-19).
 * @property credentialMode Whether to use backend mediation or the user's own key.
 * @property backendBaseUrl Base URL of the operator-run mediation backend. Blank → gate blocks with
 *   an honest "Не указан backend-endpoint" reason (only relevant for BACKEND_MEDIATION).
 * @property mediatedTranslatePath Backend path for the mediated translate call.
 * @property thinkingLevel `low` for latency-sensitive translation. Forwarded to the backend/Gemini.
 * @property streaming SSE streaming variant; reserved for future dialogue flow.
 * @property timeoutMs Hard network timeout. Never leave the default infinite (security rule).
 */
data class GeminiFlashConfig(
    val modelId: String = DEFAULT_MODEL_ID,
    val credentialMode: GeminiCredentialMode = GeminiCredentialMode.BACKEND_MEDIATION,
    val backendBaseUrl: String = "",
    val mediatedTranslatePath: String = "/v1/cloud/translate",
    val liveTokenPath: String = "/v1/cloud/live-token",
    val thinkingLevel: String = "low",
    val streaming: Boolean = false,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    /** True only when a non-blank backend base URL is configured (precondition for BACKEND_MEDIATION). */
    val hasBackend: Boolean get() = backendBaseUrl.isNotBlank()

    /** Absolute translate endpoint, or null when no backend is configured. */
    fun translateEndpoint(): String? {
        if (!hasBackend) return null
        val base = backendBaseUrl.trimEnd('/')
        val path = if (mediatedTranslatePath.startsWith('/')) mediatedTranslatePath else "/$mediatedTranslatePath"
        return base + path
    }

    companion object {
        /** Current GA fast Gemini model (GA 2026-05-19). Config default; upgradable without code. */
        const val DEFAULT_MODEL_ID = "gemini-3.5-flash"
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
