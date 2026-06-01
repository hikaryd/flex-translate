package dev.flextranslate.foundation

/**
 * The hard gate every cloud MT call must pass BEFORE any network traffic (WS5 §2.4 / §3).
 *
 * It wraps the existing [CloudOptInState.canStart] preconditions — explicit user consent, accepted
 * disclosure, and online network — and turns a failure into a product-language [Decision.Blocked]
 * reason. The reasons mirror the "missing" list already shown on `CloudScreen` so the UI and the
 * provider tell the SAME truth.
 *
 * Two credential modes are supported (see [GeminiCredentialMode]):
 *
 * **BACKEND_MEDIATION** (original path): also requires a configured backend endpoint and a live
 * backend-issued ephemeral token. The Gemini key never leaves the server.
 *
 * **OWN_KEY** (BYOK): skips backend/token checks — the user's own encrypted key is supplied by the
 * caller ([GeminiKeyStore]). Still requires consent, disclosure, and an online network.
 * The key is NEVER inspected, stored, or logged here.
 */
class CloudCallGate(
    private val stateProvider: (providerId: String) -> CloudOptInState?,
    private val config: GeminiFlashConfig,
    /** Optionally supplied for OWN_KEY mode so the gate can verify a key is present. */
    private val keyStore: GeminiKeyStore? = null,
) {
    fun evaluate(providerId: String, nowEpochMs: Long): Decision {
        val state = stateProvider(providerId)
            ?: return Decision.Blocked(REASON_DISABLED)

        if (!state.userConsented) return Decision.Blocked(REASON_NO_CONSENT)
        if (!state.disclosureAccepted) return Decision.Blocked(REASON_NO_DISCLOSURE)
        if (state.networkState != "online") return Decision.Blocked(REASON_OFFLINE)

        return when (config.credentialMode) {
            GeminiCredentialMode.OWN_KEY -> {
                // No backend or ephemeral-token check needed. A key must be present though.
                if (keyStore?.hasKey() != true) return Decision.Blocked(REASON_NO_OWN_KEY)
                // For OWN_KEY we synthesize a dummy credential so the sealed type stays unified.
                Decision.Allowed(CloudCredential(source = "own_key", expiresAtEpochMs = Long.MAX_VALUE))
            }
            GeminiCredentialMode.BACKEND_MEDIATION -> {
                // A configured backend endpoint is a precondition for the mediated path.
                if (!config.hasBackend) return Decision.Blocked(REASON_NO_BACKEND)
                val credential = state.credential
                if (credential?.isEphemeral(nowEpochMs) != true) {
                    return Decision.Blocked(REASON_NO_TOKEN)
                }
                Decision.Allowed(credential)
            }
        }
    }

    sealed interface Decision {
        data class Allowed(val credential: CloudCredential) : Decision
        data class Blocked(val userReason: String) : Decision
    }

    companion object {
        const val REASON_NO_BACKEND = "Не указан backend-endpoint (Облако)"
        const val REASON_DISABLED = "Облако выключено"
        const val REASON_NO_CONSENT = "Облако выключено — нет согласия"
        const val REASON_NO_DISCLOSURE = "Нужно принять раскрытие данных"
        const val REASON_OFFLINE = "Нет сети — облачный перевод недоступен"
        const val REASON_NO_TOKEN = "Требуется backend-токен"
        const val REASON_NO_OWN_KEY = "Gemini API-ключ не задан"
    }
}
