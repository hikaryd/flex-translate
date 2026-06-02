import Foundation

/// BYOK-клиент напрямую: шлёт POST с промптом перевода в публичный REST-эндпоинт Gemini,
/// используя ключ самого пользователя. Ключ едет ТОЛЬКО в заголовке x-goog-api-key —
/// его НИКОГДА не логируем, НИКОГДА не пихаем в тексты ошибок, НИКОГДА не храним в этом классе.
///
/// Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
/// Auth:     заголовок x-goog-api-key: <ключ пользователя> (даёт вызывающий, тут не удерживаем)
///
/// Разбор ошибок (честно, под реальность гео-ограничений):
///  - HTTP 400 с "User location is not supported" → .geoBlocked (показываем честно)
///  - HTTP 401 / 403                              → .keyRejected
///  - сбой сети / парсинга                        → .failed
///  - успех с непустым текстом                    → .ok
///
/// Зеркалит Android GeminiDirectClient.
class GeminiDirectClient: @unchecked Sendable {

    enum DirectResult: Sendable, Equatable {
        case ok(String)
        /// HTTP 400 с гео-ограничением в теле — прямой Gemini в этом регионе недоступен.
        case geoBlocked
        /// HTTP 401 или 403 — Google отклонил ключ.
        case keyRejected
        /// Ошибка транспорта, парсинга или неожиданный ответ сервера.
        case failed(String)
    }

    private let config: GeminiFlashConfig
    private static let geminiBase = "https://generativelanguage.googleapis.com/v1beta/models"

    init(config: GeminiFlashConfig) {
        self.config = config
    }

    /// Переводит text для languagePair прямым обращением к Gemini.
    /// apiKey — ключ пользователя, который достали из Keychain прямо перед этим вызовом.
    func translate(text: String, languagePair: String, apiKey: String) -> DirectResult {
        precondition(!apiKey.isEmpty, "apiKey must not be blank")
        let endpoint = "\(Self.geminiBase)/\(config.modelId):generateContent"
        let body = buildDirectRequestBody(text: text, languagePair: languagePair)
        guard let url = URL(string: endpoint) else {
            return .failed("Invalid Gemini endpoint URL")
        }
        var request = URLRequest(url: url, timeoutInterval: config.timeoutSeconds)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        // Ключ Gemini пользователя — ЕДИНСТВЕННОЕ место, где он используется.
        // Не логируем и не храним; значение заголовка не захватывается ни одним замыканием.
        request.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")
        request.httpBody = body.data(using: .utf8)

        let semaphore = DispatchSemaphore(value: 0)
        var resultData: Data?
        var httpStatus = 0
        var networkErrorMsg: String?

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                networkErrorMsg = error.localizedDescription
            } else if let http = response as? HTTPURLResponse {
                httpStatus = http.statusCode
                resultData = data
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()

        if let msg = networkErrorMsg {
            return .failed(msg)
        }
        let payload = resultData.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        return parseDirectResponse(status: httpStatus, payload: payload)
    }
}

/// Собирает тело запроса generateContent для задачи перевода.
/// В промпте только направление и текст — ни ключа, ни учётных данных.
func buildDirectRequestBody(text: String, languagePair: String) -> String {
    let parts = languagePair.components(separatedBy: "->")
    let srcCode = parts.first ?? "ru"
    let tgtCode = parts.dropFirst().first ?? "en"
    let srcName = geminiLanguageDisplayName(srcCode)
    let tgtName = geminiLanguageDisplayName(tgtCode)
    let prompt = "Translate the following \(srcName) text to \(tgtName). " +
        "Reply with only the translation, no explanation:\n\(text)"

    // Собираем JSON руками, чтобы не ловить проблемы экранирования в JSONSerialization
    let escaped = prompt
        .replacingOccurrences(of: "\\", with: "\\\\")
        .replacingOccurrences(of: "\"", with: "\\\"")
        .replacingOccurrences(of: "\n", with: "\\n")
    return "{\"contents\":[{\"parts\":[{\"text\":\"\(escaped)\"}]}]}"
}

/// Разбирает ответ Gemini generateContent.
///
/// Успех: { "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }
/// Ошибки:
///  - HTTP 400 с "User location is not supported" → .geoBlocked
///  - HTTP 401/403 → .keyRejected
///  - всё остальное вне 2xx или битый ответ → .failed
func parseDirectResponse(status: Int, payload: String) -> GeminiDirectClient.DirectResult {
    if status == 401 || status == 403 { return .keyRejected }

    if status == 400 {
        let lower = payload.lowercased()
        if lower.contains("user location is not supported") || lower.contains("location is not supported") {
            return .geoBlocked
        }
        return .failed("HTTP 400: \(payload.prefix(200))")
    }

    guard (200...299).contains(status) else {
        return .failed("HTTP \(status)")
    }

    guard let data = payload.data(using: .utf8),
          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let candidates = json["candidates"] as? [[String: Any]],
          let first = candidates.first,
          let content = first["content"] as? [String: Any],
          let parts = content["parts"] as? [[String: Any]],
          let text = parts.first?["text"] as? String,
          !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    else {
        return .failed("Unparseable or empty Gemini response")
    }
    return .ok(text.trimmingCharacters(in: .whitespacesAndNewlines))
}

private func geminiLanguageDisplayName(_ code: String) -> String {
    switch code {
    case "ru": return "Russian"
    case "en": return "English"
    case "zh": return "Chinese"
    default: return code
    }
}
