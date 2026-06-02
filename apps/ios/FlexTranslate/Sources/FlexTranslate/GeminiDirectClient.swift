import Foundation

/// BYOK direct client: POSTs a translate prompt straight to the public Gemini REST endpoint
/// using the user's own API key. The key travels ONLY in the x-goog-api-key request header —
/// it is NEVER logged, NEVER included in error messages, NEVER stored by this class.
///
/// Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
/// Auth:     x-goog-api-key: <user key> header (supplied by caller, never retained here)
///
/// Error mapping (honest, per geo-restriction reality):
///  - HTTP 400 with "User location is not supported" → .geoBlocked (surfaced honestly)
///  - HTTP 401 / 403                                  → .keyRejected
///  - network / parse failure                         → .failed
///  - success with non-empty text                     → .ok
///
/// Mirrors Android GeminiDirectClient.
class GeminiDirectClient: @unchecked Sendable {

    enum DirectResult: Sendable, Equatable {
        case ok(String)
        /// HTTP 400 with a geo-restriction body — direct Gemini is unavailable in this region.
        case geoBlocked
        /// HTTP 401 or 403 — the key was rejected by Google.
        case keyRejected
        /// Transport, parse, or unexpected server error.
        case failed(String)
    }

    private let config: GeminiFlashConfig
    private static let geminiBase = "https://generativelanguage.googleapis.com/v1beta/models"

    init(config: GeminiFlashConfig) {
        self.config = config
    }

    /// Translate text for languagePair by calling Gemini directly.
    /// apiKey must be the user's key fetched from Keychain immediately before this call.
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
        // The user's own Gemini API key — the ONLY place the key value is used.
        // It is not logged or stored; the header value is not captured by any closure.
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

/// Build the Gemini generateContent request body for a translation task.
/// The prompt encodes only the direction and text — no key, no credential.
func buildDirectRequestBody(text: String, languagePair: String) -> String {
    let parts = languagePair.components(separatedBy: "->")
    let srcCode = parts.first ?? "ru"
    let tgtCode = parts.dropFirst().first ?? "en"
    let srcName = geminiLanguageDisplayName(srcCode)
    let tgtName = geminiLanguageDisplayName(tgtCode)
    let prompt = "Translate the following \(srcName) text to \(tgtName). " +
        "Reply with only the translation, no explanation:\n\(text)"

    // Build JSON manually — no Foundation JSONSerialization issues with string escaping
    let escaped = prompt
        .replacingOccurrences(of: "\\", with: "\\\\")
        .replacingOccurrences(of: "\"", with: "\\\"")
        .replacingOccurrences(of: "\n", with: "\\n")
    return "{\"contents\":[{\"parts\":[{\"text\":\"\(escaped)\"}]}]}"
}

/// Parse a Gemini generateContent response.
///
/// Success shape: { "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }
/// Error shapes:
///  - HTTP 400 with "User location is not supported" → .geoBlocked
///  - HTTP 401/403 → .keyRejected
///  - anything else non-2xx or malformed → .failed
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
