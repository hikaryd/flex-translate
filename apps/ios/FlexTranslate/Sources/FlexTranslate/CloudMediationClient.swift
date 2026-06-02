import Foundation

/// Запрос, который приложение шлёт НАШЕМУ backend-посреднику на перевод через Gemini Flash.
/// Несёт только намерение пользователя — ключа Gemini тут нет. Backend сам подставляет
/// авторизацию, дёргает generateContent на своей стороне и возвращает только текст.
///
/// Калька с Android GeminiTranslateRequest.
struct GeminiTranslateRequest: Sendable {
    let providerId: String
    let modelId: String
    let languagePair: String
    let text: String
    let thinkingLevel: String
    let stream: Bool
}

/// Граница между GeminiFlashTranslationProvider и backend, который держит оператор.
/// Результат — явный enum, чтобы провайдер мог честно разложить любой исход на
/// TranslationResult (текст / причина отказа / ошибка транспорта) и НИКОГДА не выдумывал
/// вывод.
///
/// Калька с Android CloudMediationClient.
protocol CloudMediationClient: Sendable {
    func translate(request: GeminiTranslateRequest, credential: CloudCredential) -> CloudMediationResult
}

enum CloudMediationResult: Sendable {
    /// Backend вернул настоящий перевод, сделанный Gemini на серверной стороне.
    case ok(text: String, modelId: String?)
    /// Backend явно отказал с сообщением на языке продукта.
    case refused(String)
    /// Сбой транспорта или парсинга.
    case failed(String)
}

/// Клиент посредника поверх URLSession (без лишних зависимостей).
/// POST-ит намерение-перевод как JSON на настроенный эндпоинт backend, в Authorization
/// кладёт собственный сессионный токен приложения — НИКОГДА не ключ Gemini.
///
/// Калька с Android HttpCloudMediationClient.
final class HttpCloudMediationClient: CloudMediationClient, @unchecked Sendable {

    private let config: GeminiFlashConfig

    init(config: GeminiFlashConfig) {
        self.config = config
    }

    func translate(request: GeminiTranslateRequest, credential: CloudCredential) -> CloudMediationResult {
        guard let endpoint = config.translateEndpoint() else {
            return .failed("backend endpoint not configured")
        }
        guard let url = URL(string: endpoint) else {
            return .failed("invalid backend endpoint URL")
        }
        let body = buildMediatedRequestBody(request: request)
        var urlRequest = URLRequest(url: url, timeoutInterval: config.timeoutSeconds)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        // Сессионная идентичность приложения для нашего backend — НЕ ключ Gemini.
        urlRequest.setValue("Bearer \(credential.source)", forHTTPHeaderField: "Authorization")
        urlRequest.httpBody = body.data(using: .utf8)

        let semaphore = DispatchSemaphore(value: 0)
        var responseData: Data?
        var httpStatus = 0
        var networkError: String?

        URLSession.shared.dataTask(with: urlRequest) { data, response, error in
            if let error = error {
                networkError = error.localizedDescription
            } else if let http = response as? HTTPURLResponse {
                httpStatus = http.statusCode
                responseData = data
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()

        if let msg = networkError {
            return .failed(msg)
        }
        let payload = responseData.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        return parseMediationResponse(status: httpStatus, payload: payload)
    }
}

/// Собирает JSON-тело запроса к посреднику. Вынесено отдельно, чтобы форму запроса можно было покрыть тестами.
func buildMediatedRequestBody(request: GeminiTranslateRequest) -> String {
    func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
         .replacingOccurrences(of: "\"", with: "\\\"")
         .replacingOccurrences(of: "\n", with: "\\n")
    }
    let pid = escape(request.providerId)
    let mid = escape(request.modelId)
    let lp = escape(request.languagePair)
    let txt = escape(request.text)
    let tl = escape(request.thinkingLevel)
    let st = request.stream ? "true" : "false"
    return "{\"providerId\":\"\(pid)\",\"modelId\":\"\(mid)\",\"languagePair\":\"\(lp)\",\"text\":\"\(txt)\",\"thinkingLevel\":\"\(tl)\",\"stream\":\(st)}"
}

/// Превращает ответ backend в честный CloudMediationResult.
/// Калька с Android parseResponse.
func parseMediationResponse(status: Int, payload: String) -> CloudMediationResult {
    guard let data = payload.data(using: .utf8),
          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        return .failed("HTTP \(status): unparseable response")
    }

    if !(200...299).contains(status) {
        let message = (json["userMessage"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? "HTTP \(status)"
        return .refused(message)
    }
    let ok = json["ok"] as? Bool ?? false
    if !ok {
        let message = (json["userMessage"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? (json["reason"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? "Cloud translation declined by server"
        return .refused(message)
    }
    guard let text = json["text"] as? String, !text.isEmpty else {
        return .failed("backend ok but empty text")
    }
    let modelId = (json["modelId"] as? String).flatMap { $0.isEmpty ? nil : $0 }
    return .ok(text: text, modelId: modelId)
}
