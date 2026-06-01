import Foundation

/// Adapter seam for future sherpa-onnx integration.
/// G003 does not claim model support until benchmark reports fill WER/CER, RTF,
/// p95 latency, memory, battery, and thermal fields for the chosen candidate.
struct SherpaOnnxAsrProvider: AsrProvider {
    let candidate: AsrCandidate
    var providerId: String { "sherpa-onnx:\(candidate.id)" }

    func accept(frame: AudioFrame) -> [TranscriptEvent] {
        // Real sherpa-onnx streaming integration belongs in the benchmarked proof step.
        []
    }

    func reset() {}

    func supportClaim() -> String { candidate.support }
}
