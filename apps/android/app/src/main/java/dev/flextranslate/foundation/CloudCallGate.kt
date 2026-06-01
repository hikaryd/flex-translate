package dev.flextranslate.foundation

/**
 * The hard gate every cloud MT call must pass BEFORE any network traffic (WS5 §2.4 / §3).
 *
 * It wraps the existing [CloudOptInState.canStart] preconditions — explicit user consent, accepted
 * disclosure, online network, and a live backend-issued credential — and turns a failure into a
 * product-language [Decision.Blocked] reason. The reasons mirror the "missing" list already shown on
 * `CloudScreen` so the UI and the provider tell the SAME truth.
 *
 * For the mediated text-translation path the "credential" the app carries is its own short-lived
 * backend session, modeled (per §2.4) as a [CloudCredential] with `source == "backend_ephemeral_token"`
 * semantics — the Gemini key never leaves the server.
 */
class CloudCallGate(
    private val stateProvider: (providerId: String) -> CloudOptInState?,
    private val config: GeminiFlashConfig,
) {
    fun evaluate(providerId: String, nowEpochMs: Long): Decision {
        // A configured backend endpoint is a precondition for the mediated path: without it there is
        // no key-free way to reach Gemini, so we gate honestly instead of attempting a doomed call.
        if (!config.hasBackend) {
            return Decision.Blocked(REASON_NO_BACKEND)
        }
        val state = stateProvider(providerId)
            ?: return Decision.Blocked(REASON_DISABLED)

        if (!state.userConsented) return Decision.Blocked(REASON_NO_CONSENT)
        if (!state.disclosureAccepted) return Decision.Blocked(REASON_NO_DISCLOSURE)
        if (state.networkState != "online") return Decision.Blocked(REASON_OFFLINE)

        val credential = state.credential
        if (credential?.isEphemeral(nowEpochMs) != true) {
            return Decision.Blocked(REASON_NO_TOKEN)
        }
        return Decision.Allowed(credential)
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
    }
}
