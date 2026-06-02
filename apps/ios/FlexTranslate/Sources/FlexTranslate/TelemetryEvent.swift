import Foundation

struct TelemetryEvent: Codable, Sendable {
    let sessionId: String
    let monotonicTsMs: Int64
    let eventType: String
    let deviceTier: String
    let deviceModel: String
    let osVersion: String
    let runtimeId: String
    let modelId: String
    let languagePair: String
    let mode: String
    let networkState: String
    let appBuild: String
    let payload: [String: String]

    /// Return a copy with the given timestamp.
    func withTs(_ ts: Int64) -> TelemetryEvent {
        TelemetryEvent(
            sessionId: sessionId,
            monotonicTsMs: ts,
            eventType: eventType,
            deviceTier: deviceTier,
            deviceModel: deviceModel,
            osVersion: osVersion,
            runtimeId: runtimeId,
            modelId: modelId,
            languagePair: languagePair,
            mode: mode,
            networkState: networkState,
            appBuild: appBuild,
            payload: payload
        )
    }

    /// A zero-filled placeholder used to pre-allocate the ring buffer.
    static let placeholder = TelemetryEvent(
        sessionId: "", monotonicTsMs: 0, eventType: "",
        deviceTier: "", deviceModel: "", osVersion: "",
        runtimeId: "", modelId: "", languagePair: "",
        mode: "", networkState: "", appBuild: "", payload: [:]
    )

    /// Serialize to a single JSONL line (no newline appended).
    func toJsonLine() -> String {
        var parts = [
            "\"session_id\":\"\(sessionId.jsonEscaped)\"",
            "\"monotonic_ts_ms\":\(monotonicTsMs)",
            "\"event_type\":\"\(eventType.jsonEscaped)\"",
            "\"device_tier\":\"\(deviceTier.jsonEscaped)\"",
            "\"device_model\":\"\(deviceModel.jsonEscaped)\"",
            "\"os_version\":\"\(osVersion.jsonEscaped)\"",
            "\"runtime_id\":\"\(runtimeId.jsonEscaped)\"",
            "\"model_id\":\"\(modelId.jsonEscaped)\"",
            "\"language_pair\":\"\(languagePair.jsonEscaped)\"",
            "\"mode\":\"\(mode.jsonEscaped)\"",
            "\"network_state\":\"\(networkState.jsonEscaped)\"",
            "\"app_build\":\"\(appBuild.jsonEscaped)\"",
        ]
        if !payload.isEmpty {
            let entries = payload.sorted(by: { $0.key < $1.key })
                .map { "\"\($0.key.jsonEscaped)\":\"\($0.value.jsonEscaped)\"" }
                .joined(separator: ",")
            parts.append("\"payload\":{\(entries)}")
        }
        return "{\(parts.joined(separator: ","))}"
    }
}

private extension String {
    var jsonEscaped: String {
        self
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "\\r")
            .replacingOccurrences(of: "\t", with: "\\t")
    }
}
