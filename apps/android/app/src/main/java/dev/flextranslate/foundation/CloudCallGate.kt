package dev.flextranslate.foundation

/**
 * Жёсткий гейт, который каждый облачный MT-вызов обязан пройти ДО любого сетевого трафика
 * (WS5 §2.4 / §3).
 *
 * Оборачивает существующие предусловия [CloudOptInState.canStart] — явное согласие пользователя,
 * принятое раскрытие данных и наличие сети — и превращает отказ в причину [Decision.Blocked] на
 * продуктовом языке. Причины повторяют список «чего не хватает» с `CloudScreen`, чтобы UI и
 * провайдер говорили ОДНУ И ТУ ЖЕ правду.
 *
 * Поддерживаются два режима учётных данных (см. [GeminiCredentialMode]):
 *
 * **BACKEND_MEDIATION** (исходный путь): дополнительно нужны настроенный backend-endpoint и живой
 * эфемерный токен, выданный бэкендом. Ключ Gemini не покидает сервер.
 *
 * **OWN_KEY** (BYOK): пропускает проверки бэкенда/токена — свой зашифрованный ключ пользователя
 * передаёт вызывающий код ([GeminiKeyStore]). Согласие, раскрытие и сеть всё равно обязательны.
 * Ключ здесь НИКОГДА не читаем, не храним и не логируем.
 */
class CloudCallGate(
    private val stateProvider: (providerId: String) -> CloudOptInState?,
    private val config: GeminiFlashConfig,
    /** Передаётся опционально для режима OWN_KEY, чтобы гейт мог проверить наличие ключа. */
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
                // Бэкенд и эфемерный токен не проверяем. Но ключ должен быть.
                if (keyStore?.hasKey() != true) return Decision.Blocked(REASON_NO_OWN_KEY)
                // Для OWN_KEY подсовываем заглушку-credential, чтобы sealed-тип остался единым.
                Decision.Allowed(CloudCredential(source = "own_key", expiresAtEpochMs = Long.MAX_VALUE))
            }
            GeminiCredentialMode.BACKEND_MEDIATION -> {
                // Настроенный backend-endpoint — обязательное условие для медиированного пути.
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
