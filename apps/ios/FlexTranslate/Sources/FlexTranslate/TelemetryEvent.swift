import Foundation

struct TelemetryEvent: Codable {
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
}
