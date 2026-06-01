package dev.flextranslate.foundation

/**
 * The real WS5 cloud MT tier behind the existing `gemini-flash-cloud` picker candidate. It
 * implements [TranslationProvider] verbatim and replaces the old "реальный вызов появится в WS5"
 * placeholder.
 *
 * Security (WS5 §2): the app holds **no Gemini API key**. Translation goes through OUR mediation
 * backend ([CloudMediationClient]), which injects the Gemini credential server-side and returns text
 * only. Every call is hard-gated by [CloudCallGate] (consent + disclosure + online + live
 * backend-issued credential) BEFORE any network traffic.
 *
 * Honesty (§1.2 / §3): a gated, offline, declined, or failed call returns
 * `TranslationResult(text = null, unsupportedReason = <product-language reason>)`. It **never**
 * fabricates output and **never** silently falls back to an on-device model — switching tiers is a
 * user action, surfaced by the honest reason.
 *
 * Model id is config-driven ([GeminiFlashConfig.modelId], default `gemini-3.5-flash`).
 */
class GeminiFlashTranslationProvider(
    private val config: GeminiFlashConfig,
    private val gate: CloudCallGate,
    private val backend: CloudMediationClient,
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

        // 1. Hard gate. No consent / no disclosure / offline / no credential / no backend → no call.
        val decision = gate.evaluate(providerId, clock())
        if (decision is CloudCallGate.Decision.Blocked) {
            return TranslationResult(text = null, unsupportedReason = decision.userReason)
        }
        val allowed = decision as CloudCallGate.Decision.Allowed

        // 2. Build the mediated translate request (intent only — no Gemini credential travels here).
        val request = GeminiTranslateRequest(
            providerId = providerId,
            modelId = config.modelId,
            languagePair = languagePair,
            text = trimmed,
            thinkingLevel = config.thinkingLevel,
            stream = config.streaming,
        )

        // 3. Mediated call — backend injects auth, calls Gemini, returns text only.
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
    }
}
