package dev.flextranslate.foundation

data class TranslationCandidate(
    val id: String,
    val candidateClass: String,
    val model: String,
    val runtimeCandidates: List<String>,
    val languagePairs: List<String>,
    val targetTiers: List<String>,
    val support: String = "not_claimed",
)

object TranslationCandidateRegistry {
    val candidates = listOf(
        TranslationCandidate(
            id = "milmmt-46-4b-q6-gguf-high-tier",
            candidateClass = "high_tier_llm_mt",
            model = "MiLMMT-46-4B-v0.1 Q6/GGUF community quantization",
            runtimeCandidates = listOf("llama.cpp/GGUF", "MLC LLM if convertible", "LiteRT-LM if convertible"),
            languagePairs = listOf("ru->en", "en->ru"),
            targetTiers = listOf("high"),
        ),
        TranslationCandidate(
            id = "dedicated-mt-low-mid",
            candidateClass = "dedicated_mt_model",
            model = "NLLB/M2M/Marian/mobile-convertible candidate TBD",
            runtimeCandidates = listOf("ONNX", "LiteRT", "other_mobile_runtime"),
            languagePairs = listOf("ru->en", "en->ru"),
            targetTiers = listOf("low", "mid"),
        ),
    )
}
