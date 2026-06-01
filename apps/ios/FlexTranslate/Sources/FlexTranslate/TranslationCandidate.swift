import Foundation

struct TranslationCandidate: Equatable {
    let id: String
    let candidateClass: String
    let model: String
    let runtimeCandidates: [String]
    let languagePairs: [String]
    let targetTiers: [String]
    let support: String
}

enum TranslationCandidateRegistry {
    static let candidates: [TranslationCandidate] = [
        TranslationCandidate(
            id: "milmmt-46-4b-q6-gguf-high-tier",
            candidateClass: "high_tier_llm_mt",
            model: "MiLMMT-46-4B-v0.1 Q6/GGUF community quantization",
            runtimeCandidates: ["llama.cpp/GGUF", "MLC LLM if convertible", "LiteRT-LM if convertible"],
            languagePairs: ["ru->en", "en->ru"],
            targetTiers: ["high"],
            support: "not_claimed"
        ),
        TranslationCandidate(
            id: "dedicated-mt-low-mid",
            candidateClass: "dedicated_mt_model",
            model: "NLLB/M2M/Marian/mobile-convertible candidate TBD",
            runtimeCandidates: ["ONNX", "LiteRT", "other_mobile_runtime"],
            languagePairs: ["ru->en", "en->ru"],
            targetTiers: ["low", "mid"],
            support: "not_claimed"
        )
    ]
}
