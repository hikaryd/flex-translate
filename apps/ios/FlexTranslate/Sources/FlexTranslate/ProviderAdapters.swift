import Foundation

// Sendable: AudioFrame ходит через границу render-потока захвата -> @MainActor
// в LiveSessionModel; остальные — value-данные на тех же стыках адаптеров.
// Явное соответствие делает контракт перехода между изоляциями осознанным, а не
// держится на хрупком неявном выводе компилятора (Swift 6).
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

// Offline-перевод включаем только при наличии бенчмарк-пруфов + скачанной MT-модели
// (WS4). До тех пор этот адаптер ничего не выдумывает — для любой пары/тира честно
// отдаёт причину «не поддерживается».
struct GatedTranslationProvider: TranslationProvider {
    let providerId = "gated-offline-mt"
    func translate(text: String, languagePair: String, deviceTier: String) -> TranslationResult {
        TranslationResult(
            text: nil,
            unsupportedReason: "перевод недоступен offline для \(languagePair)/\(deviceTier)"
        )
    }
}
