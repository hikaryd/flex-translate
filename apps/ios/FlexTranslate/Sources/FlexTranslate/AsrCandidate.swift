import Foundation

struct AsrCandidate: Equatable {
    let id: String
    let language: String
    let model: String
    let runtime: String
    let deviceTiers: [String]
    let support: String
}

enum AsrCandidateRegistry {
    static let candidates: [AsrCandidate] = [
        AsrCandidate(
            id: "ru-t-one-streaming-2025-09-08",
            language: "ru",
            model: "sherpa-onnx-streaming-t-one-russian-2025-09-08",
            runtime: "sherpa-onnx",
            deviceTiers: ["low", "mid", "high"],
            support: "not_claimed"
        ),
        AsrCandidate(
            id: "en-zipformer-20m-low-tier-2023-02-17",
            language: "en",
            model: "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            runtime: "sherpa-onnx",
            deviceTiers: ["low"],
            support: "not_claimed"
        ),
        AsrCandidate(
            id: "en-zipformer-mid-high-2023-06-26",
            language: "en",
            model: "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
            runtime: "sherpa-onnx",
            deviceTiers: ["mid", "high"],
            support: "not_claimed"
        )
    ]
}
