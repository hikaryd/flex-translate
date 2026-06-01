import Foundation

// Sendable: AudioFrame crosses the capture (audio render) thread -> @MainActor
// boundary in LiveSessionModel; the others are value payloads across the same
// adapter seams. Explicit conformance makes the cross-isolation contract
// intentional rather than relying on fragile implicit inference (Swift 6).
struct AudioFrame: Sendable {
    let pcm16: [Int16]
    let sampleRateHz: Int
    let monotonicTsMs: Int64
}

struct TranscriptEvent: Sendable {
    let text: String
    let isFinal: Bool
    let monotonicTsMs: Int64
}

struct TranslationResult: Sendable {
    let text: String?
    let unsupportedReason: String?
}

struct CloudConsent: Sendable {
    let enabled: Bool
    let credentialSource: String?
}

protocol AsrProvider {
    var providerId: String { get }
    func accept(frame: AudioFrame) -> [TranscriptEvent]
    func reset()
}

protocol TranslationProvider {
    var providerId: String { get }
    func translate(text: String, languagePair: String, deviceTier: String) -> TranslationResult
}

protocol CloudProvider {
    var providerId: String { get }
    func isAvailable(consent: CloudConsent) -> Bool
}

struct PlaceholderLocalAsrProvider: AsrProvider {
    let providerId = "placeholder-local-asr"
    func accept(frame: AudioFrame) -> [TranscriptEvent] { [] }
    func reset() {}
}

// Offline translation is gated on benchmark evidence + a downloaded MT model
// (WS4). Until then this adapter never fabricates output — it reports an
// explicit unsupported reason for every pair/tier.
struct GatedTranslationProvider: TranslationProvider {
    let providerId = "gated-offline-mt"
    func translate(text: String, languagePair: String, deviceTier: String) -> TranslationResult {
        TranslationResult(
            text: nil,
            unsupportedReason: "перевод недоступен offline для \(languagePair)/\(deviceTier)"
        )
    }
}
