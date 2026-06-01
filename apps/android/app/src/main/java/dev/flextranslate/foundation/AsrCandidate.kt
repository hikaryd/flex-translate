package dev.flextranslate.foundation

data class AsrCandidate(
    val id: String,
    val language: String,
    val model: String,
    val runtime: String,
    val deviceTiers: List<String>,
    val support: String = "not_claimed",
)

object AsrCandidateRegistry {
    val candidates = listOf(
        AsrCandidate(
            id = "ru-t-one-streaming-2025-09-08",
            language = "ru",
            model = "sherpa-onnx-streaming-t-one-russian-2025-09-08",
            runtime = "sherpa-onnx",
            deviceTiers = listOf("low", "mid", "high"),
        ),
        AsrCandidate(
            id = "en-zipformer-20m-low-tier-2023-02-17",
            language = "en",
            model = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            runtime = "sherpa-onnx",
            deviceTiers = listOf("low"),
        ),
        AsrCandidate(
            id = "en-zipformer-mid-high-2023-06-26",
            language = "en",
            model = "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
            runtime = "sherpa-onnx",
            deviceTiers = listOf("mid", "high"),
        ),
        AsrCandidate(
            id = "zh-en-bilingual-zipformer-2023-02-20",
            language = "zh",
            model = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            runtime = "sherpa-onnx",
            deviceTiers = listOf("mid", "high"),
        ),
    )
}
