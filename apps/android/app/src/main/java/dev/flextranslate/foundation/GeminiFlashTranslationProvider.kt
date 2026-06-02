package dev.flextranslate.foundation

/**
 * Реальный облачный MT-уровень WS5 за кандидатом `gemini-flash-cloud` в пикере.
 *
 * Два режима credential (см. [GeminiCredentialMode]):
 *
 * **BACKEND_MEDIATION** (изначальный путь): перевод идёт через НАШ mediation-бэкенд
 * ([CloudMediationClient]), который подставляет credential Gemini на сервере и возвращает только
 * текст. Приложение ключ Gemini вообще не держит. Гейтится через [CloudCallGate] (согласие +
 * раскрытие + online + ephemeral-токен + настроенный бэкенд).
 *
 * **OWN_KEY** (BYOK): собственный API-ключ Gemini юзера (хранится зашифрованным в [GeminiKeyStore])
 * достаётся в момент вызова и уходит в `x-goog-api-key` напрямую на REST-эндпоинт Gemini через
 * [GeminiDirectClient]. Тоже гейтится (согласие + раскрытие + online + есть ключ). Гео-ограничение
 * (HTTP 400 "User location is not supported") показываем честно — не делаем вид, что вызов прошёл
 * там, где Gemini заблокирован по региону.
 *
 * Контракт честности (§1.2 / §3): заблокированный гейтом, офлайновый, отклонённый или упавший вызов
 * возвращает `TranslationResult(text = null, unsupportedReason = <причина на языке продукта>)`.
 * Никогда не выдумывает вывод и не сваливается молча на on-device модель.
 *
 * Id модели берётся из конфига ([GeminiFlashConfig.modelId], по умолчанию `gemini-3.5-flash`).
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

        // Только заявленные демо-направления с пивотом через RU; всё за рамками отбрасываем до того,
        // как тратить облачный вызов.
        MtDirection.parse(languagePair)
            ?: return TranslationResult(
                text = null,
                unsupportedReason = "пара $languagePair не поддерживается облачной моделью",
            )

        // 1. Жёсткий гейт. Нет согласия / раскрытия / офлайн → вызова нет (оба режима).
        //    Плюс: нет бэкенда → блок (mediation); нет ключа → блок (own-key).
        val decision = gate.evaluate(providerId, clock())
        if (decision is CloudCallGate.Decision.Blocked) {
            return TranslationResult(text = null, unsupportedReason = decision.userReason)
        }
        val allowed = decision as CloudCallGate.Decision.Allowed

        // 2. Выбираем транспорт по режиму credential.
        return when (config.credentialMode) {
            GeminiCredentialMode.OWN_KEY -> translateDirect(trimmed, languagePair)
            GeminiCredentialMode.BACKEND_MEDIATION -> translateMediated(trimmed, languagePair, allowed)
        }
    }

    /** Путь OWN_KEY: достаём ключ из защищённого хранилища в момент вызова, бьём в Gemini напрямую. */
    private fun translateDirect(text: String, languagePair: String): TranslationResult {
        // Ключ берём только на время вызова — между вызовами не держим.
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

    /** Путь BACKEND_MEDIATION: собираем запрос-намерение и отдаём его mediation-бэкенду. */
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

        // Честные причины для прямого пути OWN_KEY — показываются на экране Live.
        const val REASON_GEO_BLOCKED =
            "Gemini недоступен в вашем регионе — используйте backend-режим или VPN"
        const val REASON_KEY_REJECTED =
            "Gemini API-ключ отклонён (проверьте ключ в настройках Облака)"
        const val REASON_DIRECT_FAILED =
            "Прямой вызов Gemini не удался"
    }
}
