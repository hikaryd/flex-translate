import Foundation

/// The request the app sends to OUR mediation backend for a Gemini Flash translate.
/// Carries user intent only — never a Gemini credential. The backend injects auth, calls
/// Gemini's generateContent server-side, and returns text only.
///
/// Mirrors Android GeminiTranslateRequest.
struct GeminiTranslateRequest: Sendable {
    let providerId: String
    let modelId: String
    let languagePair: String
    let text: String
    let thinkingLevel: String
    let stream: Bool
}

/// Seam between GeminiFlashTranslationProvider and the operator-run backend.
/// The result is an explicit enum so the provider can map every outcome to an honest
/// TranslationResult — success text, declined reason, or transport failure — and NEVER
/// fabricate output.
///
/// Mirrors Android CloudMediationClient.
protocol CloudMediationClient: Sendable {
    func translate(request: GeminiTranslateRequest, credential: CloudCredential) -> CloudMediationResult
}

enum CloudMediationResult: Sendable {
    /// Backend returned a real translation produced by Gemini server-side.
    case ok(text: String, modelId: String?)
    /// Backend explicitly declined with a product-language message.
    case refused(String)
    /// Transport/parse failure.
    case failed(String)
}

/// Real backend-mediation client over URLSession (no extra dependencies).
/// POSTs the translate intent as JSON to the configured backend endpoint with
/// the app's own session token in Authorization — NEVER a Gemini key.
///
/// Mirrors Android HttpCloudMediationClient.
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
        // The app's own backend session identity — NOT a Gemini API key.
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

/// Build the mediated-translate JSON body. Extracted so the request shape is unit-testable.
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

/// Parse the backend's response into an honest CloudMediationResult.
/// Mirrors Android parseResponse.
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
