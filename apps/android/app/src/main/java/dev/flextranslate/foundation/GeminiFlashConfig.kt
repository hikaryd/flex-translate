package dev.flextranslate.foundation

/**
 * Настройки облачного MT-тира Gemini Flash (`gemini-flash-cloud`, WS5).
 *
 * Конкретный id модели Gemini держим в конфиге, а не зашиваем в код (docs/design/ws5-gemini-flash.md §4):
 * так "3.5 Flash" или что-то поновее можно поменять без правки кода.
 *
 * Два режима работы (см. [GeminiCredentialMode]):
 *
 * **[GeminiCredentialMode.BACKEND_MEDIATION]** (исходный путь WS5): тут endpoint'ы НАШЕГО бэкенда,
 * а не Google. Приложение никогда не держит host + ключ Google вместе и не везёт с собой ключ
 * Gemini. Реальный ключ подставляет бэкенд на своей стороне и возвращает уже переведённый текст.
 *
 * **[GeminiCredentialMode.OWN_KEY]** (BYOK): пользователь даёт свой ключ Gemini, он хранится
 * зашифрованным на устройстве (EncryptedSharedPreferences). Приложение бьёт прямо в публичный REST
 * Gemini. Работает там, где Gemini не заблокирован по гео; если регион не поддерживается — честно
 * показываем это (HTTP 400 "User location is not supported").
 *
 * @property modelId id модели Gemini. По умолчанию `gemini-3.5-flash` (GA 2026-05-19).
 * @property credentialMode через бэкенд-посредник или со своим ключом.
 * @property backendBaseUrl базовый URL бэкенда-посредника. Пусто → гейт честно блокирует с причиной
 *   "Не указан backend-endpoint" (актуально только для BACKEND_MEDIATION).
 * @property mediatedTranslatePath путь на бэкенде для вызова перевода через посредника.
 * @property thinkingLevel `low` ради скорости — перевод чувствителен к задержке. Прокидываем на бэкенд/Gemini.
 * @property streaming вариант с SSE-стримингом; задел под будущий диалоговый поток.
 * @property timeoutMs жёсткий сетевой таймаут. Никогда не оставлять бесконечный дефолт (правило безопасности).
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
    /** True, только если задан непустой базовый URL бэкенда (без него BACKEND_MEDIATION не запустить). */
    val hasBackend: Boolean get() = backendBaseUrl.isNotBlank()

    /** Полный URL endpoint'а перевода или null, если бэкенд не настроен. */
    fun translateEndpoint(): String? {
        if (!hasBackend) return null
        val base = backendBaseUrl.trimEnd('/')
        val path = if (mediatedTranslatePath.startsWith('/')) mediatedTranslatePath else "/$mediatedTranslatePath"
        return base + path
    }

    companion object {
        /** Текущая быстрая GA-модель Gemini (GA 2026-05-19). Дефолт из конфига, апгрейдится без правки кода. */
        const val DEFAULT_MODEL_ID = "gemini-3.5-flash"
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
