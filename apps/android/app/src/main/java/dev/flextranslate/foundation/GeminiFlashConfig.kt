package dev.flextranslate.foundation

/**
 * Config for the WS5 Gemini Flash cloud MT tier (`gemini-flash-cloud`).
 *
 * Per `docs/design/ws5-gemini-flash.md` §4 the concrete Gemini model id is **config, never a
 * literal**, so "3.5 Flash" / the latest can change with no code edit. Crucially, the endpoints
 * here are **OUR backend's**, not Google's — the app never holds Google's host + key together, and
 * ships **no Gemini API key** (§2.1 "No embedded API keys"). The backend injects the real Gemini
 * credential server-side (backend mediation) and returns translated text only.
 *
 * @property modelId Gemini model id the backend should use. Default is the current GA fast model
 *   `gemini-3.5-flash` (GA 2026-05-19); the operator can also pin/override it server-side.
 * @property backendBaseUrl Base URL of the operator-run mediation backend (e.g.
 *   `https://flex-backend.example.com`). Blank when the user has not configured one — the provider
 *   then gates honestly ("Не указан backend-endpoint") instead of attempting a call.
 * @property mediatedTranslatePath Backend path for the mediated `generateContent` translate call.
 * @property thinkingLevel `low` for latency-sensitive translation (§1.4). Sent as request intent;
 *   the backend forwards it to Gemini's `generationConfig.thinkingConfig.thinkingLevel`.
 * @property streaming Request the SSE streaming variant. Reserved for the dialogue flow (§5); the
 *   current batch MT tier uses the non-streaming path. Kept here so the id moves without code churn.
 * @property timeoutMs Hard network timeout. Never leave the default infinite (security rule).
 */
data class GeminiFlashConfig(
    val modelId: String = DEFAULT_MODEL_ID,
    val backendBaseUrl: String = "",
    val mediatedTranslatePath: String = "/v1/cloud/translate",
    val liveTokenPath: String = "/v1/cloud/live-token",
    val thinkingLevel: String = "low",
    val streaming: Boolean = false,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    /** True only when a non-blank backend base URL is configured (precondition for any cloud call). */
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
