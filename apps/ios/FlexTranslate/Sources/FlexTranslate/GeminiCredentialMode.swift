import Foundation

/// Как приложение ходит в Gemini для облачного перевода.
///
/// backendMediation — исходный путь: перевод идёт через наш backend, ключ Gemini лежит
/// на сервере, наружу отдаётся только текст. Приложение никогда не держит ключ Gemini —
/// backend выдаёт короткоживущие сессионные токены.
///
/// ownKey — BYOK: пользователь приносит свой ключ Gemini, он лежит в Keychain
/// (не в UserDefaults и не в логах). Приложение шлёт POST напрямую в публичный REST Gemini.
/// Работает там, где Gemini доступен; где нет — честно показываем гео-ограничение.
///
/// Зеркалит Android GeminiCredentialMode.
enum GeminiCredentialMode: String, Sendable {
    case backendMediation = "BACKEND_MEDIATION"
    case ownKey = "OWN_KEY"
}
