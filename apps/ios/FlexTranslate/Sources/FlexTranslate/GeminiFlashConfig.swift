import Foundation

/// Конфиг облачного MT-тира Gemini Flash из WS5.
///
/// Конкретный id модели Gemini — это конфиг, а не литерал, чтобы менять его без правки кода.
///
/// Поддерживаем два режима (см. GeminiCredentialMode):
///
/// backendMediation (исходный путь): перевод идёт через НАШ backend, он подставляет
/// учётку Gemini на сервере и возвращает только переведённый текст. Ключ Gemini в приложении не лежит.
///
/// ownKey (BYOK): пользователь даёт свой ключ Gemini, он хранится в iOS Keychain.
/// Приложение POST'ит напрямую в публичный REST-endpoint Gemini. Гео-ограничение показываем честно.
///
/// Зеркалит Android GeminiFlashConfig.
struct GeminiFlashConfig: Sendable {
    let modelId: String
    let credentialMode: GeminiCredentialMode
    let backendBaseUrl: String
    let mediatedTranslatePath: String
    let thinkingLevel: String
    let streaming: Bool
    let timeoutSeconds: Double

    init(
        modelId: String = Self.defaultModelId,
        credentialMode: GeminiCredentialMode = .backendMediation,
        backendBaseUrl: String = "",
        mediatedTranslatePath: String = "/v1/cloud/translate",
        thinkingLevel: String = "low",
        streaming: Bool = false,
        timeoutSeconds: Double = 15
    ) {
        self.modelId = modelId
        self.credentialMode = credentialMode
        self.backendBaseUrl = backendBaseUrl
        self.mediatedTranslatePath = mediatedTranslatePath
        self.thinkingLevel = thinkingLevel
        self.streaming = streaming
        self.timeoutSeconds = timeoutSeconds
    }

    /// Текущая быстрая GA-модель Gemini. Обновляется без правки кода.
    static let defaultModelId = "gemini-3.5-flash"

    /// True, только если задан непустой базовый URL бэкенда.
    var hasBackend: Bool { !backendBaseUrl.trimmingCharacters(in: .whitespaces).isEmpty }

    /// Абсолютный endpoint перевода или nil, если backend не настроен.
    func translateEndpoint() -> String? {
        guard hasBackend else { return nil }
        let base = backendBaseUrl.hasSuffix("/")
            ? String(backendBaseUrl.dropLast())
            : backendBaseUrl
        let path = mediatedTranslatePath.hasPrefix("/")
            ? mediatedTranslatePath
            : "/\(mediatedTranslatePath)"
        return base + path
    }
}
