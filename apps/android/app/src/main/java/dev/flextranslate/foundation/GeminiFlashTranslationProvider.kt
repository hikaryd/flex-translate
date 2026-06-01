package dev.flextranslate.foundation

/**
 * The real WS5 cloud MT tier behind the `gemini-flash-cloud` picker candidate.
 *
 * Supports two credential modes (see [GeminiCredentialMode]):
 *
 * **BACKEND_MEDIATION** (original path): translation goes through OUR mediation backend
 * ([CloudMediationClient]), which injects the Gemini credential server-side and returns text only.
 * The app never holds a Gemini key. Gated by [CloudCallGate] (consent + disclosure + online +
 * ephemeral token + backend configured).
 *
 * **OWN_KEY** (BYOK): the user's own Gemini API key (encrypted at rest in [GeminiKeyStore]) is
 * fetched just-in-time and sent in `x-goog-api-key` directly to the Gemini REST endpoint via
 * [GeminiDirectClient]. Still gated (consent + disclosure + online + key present). Geo-restriction
 * (HTTP 400 "User location is not supported") is surfaced honestly — the app never pretends the
 * call succeeded where Gemini is blocked by region.
 *
 * Honesty contract (§1.2 / §3): a gated, offline, declined, or failed call returns
 * `TranslationResult(text = null, unsupportedReason = <product-language reason>)`. Never
 * fabricates output, never silently falls back to an on-device model.
 *
 * Model id is config-driven ([GeminiFlashConfig.modelId], default `gemini-3.5-flash`).
 */
class GeminiFlashTranslationProvider(
    private val config: GeminiFlashConfig,
    private val gate: CloudCallGate,
    private val backend: CloudMediationClient,
    private val directClient: GeminiDirectClient = GeminiDirectClient(config),
    private val keyStore: GeminiKeyStore? = null,
    override val providerId: String = PROVIDER_ID,
    private val clock: () -> Long = System::currentTimeMillis,
) : TranslationProvider {

    override fun translate(text: String, languagePair: String, deviceTier: String): TranslationResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TranslationResult(text = null, unsupportedReason = null)

        // Restrict to the advertised RU-pivot demo directions; reject anything outside scope before
        // spending a cloud call.
        MtDirection.parse(languagePair)
            ?: return TranslationResult(
                text = null,
                unsupportedReason = "пара $languagePair не поддерживается облачной моделью",
            )

        // 1. Hard gate. No consent / no disclosure / offline → no call (both modes).
        //    Additionally: no backend → blocked (mediation); no key → blocked (own-key).
        val decision = gate.evaluate(providerId, clock())
        if (decision is CloudCallGate.Decision.Blocked) {
            return TranslationResult(text = null, unsupportedReason = decision.userReason)
        }
        val allowed = decision as CloudCallGate.Decision.Allowed

        // 2. Route to the appropriate transport based on the credential mode.
        return when (config.credentialMode) {
            GeminiCredentialMode.OWN_KEY -> translateDirect(trimmed, languagePair)
            GeminiCredentialMode.BACKEND_MEDIATION -> translateMediated(trimmed, languagePair, allowed)
        }
    }

    /** OWN_KEY path: fetch key from secure storage just-in-time, call Gemini directly. */
    private fun translateDirect(text: String, languagePair: String): TranslationResult {
        // Load key from secure storage at call time — never hold it across calls.
        val apiKey = keyStore?.loadKey()
        if (apiKey.isNullOrBlank()) {
            return TranslationResult(text = null, unsupportedReason = CloudCallGate.REASON_NO_OWN_KEY)
        }
        return when (val result = directClient.translate(text, languagePair, apiKey)) {
            is GeminiDirectClient.DirectResult.Ok ->
                TranslationResult(text = result.text, unsupportedReason = null)
            GeminiDirectClient.DirectResult.GeoBlocked ->
                TranslationResult(text = null, unsupportedReason = REASON_GEO_BLOCKED)
            GeminiDirectClient.DirectResult.KeyRejected ->
                TranslationResult(text = null, unsupportedReason = REASON_KEY_REJECTED)
            is GeminiDirectClient.DirectResult.Failed ->
                TranslationResult(text = null, unsupportedReason = REASON_DIRECT_FAILED)
        }
    }

    /** BACKEND_MEDIATION path: build intent request and delegate to the mediation backend. */
    private fun translateMediated(
        text: String,
        languagePair: String,
        allowed: CloudCallGate.Decision.Allowed,
    ): TranslationResult {
        val request = GeminiTranslateRequest(
            providerId = providerId,
            modelId = config.modelId,
            languagePair = languagePair,
            text = text,
            thinkingLevel = config.thinkingLevel,
            stream = config.streaming,
        )
        return when (val result = backend.translate(request, allowed.credential)) {
            is CloudMediationClient.Result.Ok ->
                TranslationResult(text = result.text, unsupportedReason = null)
            is CloudMediationClient.Result.Refused ->
                TranslationResult(text = null, unsupportedReason = result.userReason)
            is CloudMediationClient.Result.Failed ->
                TranslationResult(text = null, unsupportedReason = "Облачный перевод недоступен")
        }
    }

    companion object {
        const val PROVIDER_ID = "gemini-flash-cloud"

        // Honest reasons for the OWN_KEY direct path — shown on the Live screen.
        const val REASON_GEO_BLOCKED =
            "Gemini недоступен в вашем регионе — используйте backend-режим или VPN"
        const val REASON_KEY_REJECTED =
            "Gemini API-ключ отклонён (проверьте ключ в настройках Облака)"
        const val REASON_DIRECT_FAILED =
            "Прямой вызов Gemini не удался"
    }
}
