package dev.flextranslate.foundation

/**
 * Как приложение ходит в Gemini для облачного MT-перевода.
 *
 * [BACKEND_MEDIATION] — изначальный путь (WS5): перевод идёт через backend оператора, который держит
 * ключ Gemini на сервере и возвращает только текст. Приложение никогда не держит учётку Gemini —
 * вместо этого backend выдаёт короткоживущие эфемерные токены сессии.
 *
 * [OWN_KEY] — BYOK («свой ключ»): пользователь даёт собственный API-ключ Gemini, который хранится
 * зашифрованным (EncryptedSharedPreferences / AES-256-GCM + KeyStore). Приложение шлёт POST напрямую
 * на публичный REST-endpoint Gemini с ключом пользователя в заголовке `x-goog-api-key`. Работает там,
 * где Gemini доступен; где нет — честно показывает геоблокировку. Ключ НИКОГДА не логируется,
 * не печатается и не попадает ни в одно сообщение об ошибке.
 */
enum class GeminiCredentialMode {
    BACKEND_MEDIATION,
    OWN_KEY,
}
