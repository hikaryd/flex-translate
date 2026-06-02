import Foundation

/// Один скачиваемый файл из пакета модели: имя файла на устройстве (его ждут хранилища),
/// resolve-URL на Hugging Face, ожидаемый SHA-256 и размер в байтах.
///
/// Зеркалит Android ModelFileDownload.
struct ModelFileDownload: Sendable {
    let fileName: String
    let sourceUrl: String
    let sha256: String
    let sizeBytes: Int64
}

/// Целиком скачиваемый пакет модели: modelId (подпапка внутри models/) и
/// упорядоченный список файлов, без которых модель неполная.
///
/// Зеркалит Android ModelDownloadSpec.
struct ModelDownloadSpec: Sendable {
    let modelId: String
    let files: [ModelFileDownload]

    var totalBytes: Int64 { files.reduce(0) { $0 + $1.sizeBytes } }
    var totalMb: Double { Double(totalBytes) / (1024.0 * 1024.0) }
}

/// Реестр всех скачиваемых пакетов по modelId.
/// Имена файлов — КОРОТКИЕ имена на устройстве, которые грузит рантайм; URL/sha256/размеры — из upstream.
///
/// Зеркалит Android ModelDownloadSpecs.
enum ModelDownloadSpecs {
    private static let hf = "https://huggingface.co"

    /// RU основная: T-one streaming CTC (Apache-2.0) — model.onnx + tokens.txt.
    static let ruTOne = ModelDownloadSpec(
        modelId: AsrModelSpecs.ruTOne.modelId,
        files: [
            ModelFileDownload(
                fileName: "model.onnx",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08/resolve/main/model.onnx",
                sha256: "5ded080e2a6c86ecc11bcb0902d77524eb3e8b0844cb0c0754347f5aafb4dabc",
                sizeBytes: 144_193_702
            ),
            ModelFileDownload(
                fileName: "tokens.txt",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08/resolve/main/tokens.txt",
                sha256: "27f7b3ba2096c572375fba1a6b29af1f80d86e08a329940612908112695f97e0",
                sizeBytes: 202
            ),
        ]
    )

    /// EN mid/high: streaming zipformer transducer int8 (Apache-2.0). Для среднего и высокого тиров.
    static let enZipformer = ModelDownloadSpec(
        modelId: AsrModelSpecs.enZipformer.modelId,
        files: [
            ModelFileDownload(
                fileName: "encoder.int8.onnx",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                sha256: "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1",
                sizeBytes: 71_083_163
            ),
            ModelFileDownload(
                fileName: "decoder.int8.onnx",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                sha256: "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02",
                sizeBytes: 1_307_236
            ),
            ModelFileDownload(
                fileName: "joiner.int8.onnx",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                sha256: "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297",
                sizeBytes: 259_335
            ),
            ModelFileDownload(
                fileName: "tokens.txt",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/tokens.txt",
                sha256: "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
                sizeBytes: 5_048
            ),
        ]
    )

    /// ZH/EN двуязычная: streaming zipformer transducer int8 (Apache-2.0).
    static let zhEnBilingual = ModelDownloadSpec(
        modelId: AsrModelSpecs.zhEnBilingual.modelId,
        files: [
            ModelFileDownload(
                fileName: "encoder.int8.onnx",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.int8.onnx",
                sha256: "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
                sizeBytes: 181_895_032
            ),
            ModelFileDownload(
                fileName: "decoder.int8.onnx",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/decoder-epoch-99-avg-1.int8.onnx",
                sha256: "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa",
                sizeBytes: 13_091_040
            ),
            ModelFileDownload(
                fileName: "joiner.int8.onnx",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/joiner-epoch-99-avg-1.int8.onnx",
                sha256: "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0",
                sizeBytes: 3_228_404
            ),
            ModelFileDownload(
                fileName: "tokens.txt",
                sourceUrl: "\(hf)/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/tokens.txt",
                sha256: "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
                sizeBytes: 56_317
            ),
        ]
    )

    /// M2M-100 418M ONNX (MIT) — сбалансированный on-device MT-пакет.
    static let m2m100418M = ModelDownloadSpec(
        modelId: MtModelSpecs.m2m100418M.modelId,
        files: [
            ModelFileDownload(
                fileName: "encoder_model_quantized.onnx",
                sourceUrl: "\(hf)/Xenova/m2m100_418M/resolve/main/onnx/encoder_model_quantized.onnx",
                sha256: "13a94e354a9140764eb81102d77d3ec6952d796e6f113c651eeb3c3443da0386",
                sizeBytes: 287_856_370
            ),
            ModelFileDownload(
                fileName: "decoder_model_quantized.onnx",
                sourceUrl: "\(hf)/Xenova/m2m100_418M/resolve/main/onnx/decoder_model_quantized.onnx",
                sha256: "6015e31c8976659aedb06058c4dadf0f400d087a3f9830f838e68f220d79bcb6",
                sizeBytes: 339_181_945
            ),
            ModelFileDownload(
                fileName: "decoder_with_past_model_quantized.onnx",
                sourceUrl: "\(hf)/Xenova/m2m100_418M/resolve/main/onnx/decoder_with_past_model_quantized.onnx",
                sha256: "780982a10d1a966978d74c210558a1d733959625c718ca7f72ddfd4ba56a23a5",
                sizeBytes: 313_662_487
            ),
            ModelFileDownload(
                fileName: "tokenizer.json",
                sourceUrl: "\(hf)/Xenova/m2m100_418M/resolve/main/tokenizer.json",
                sha256: "03d9e111731c2d71f39a2c2a88499743e4c251385d07f0384b4349a23ba54363",
                sizeBytes: 7_988_527
            ),
        ]
    )

    /// MiLMMT-46-4B Q6_K GGUF — один файл на ~3.74 ГБ.
    static let milmmt46B = ModelDownloadSpec(
        modelId: "milmmt-46-4b-q6",
        files: [
            ModelFileDownload(
                fileName: "MiLMMT-46-4B-v0.1.Q6_K.gguf",
                sourceUrl: "\(hf)/mradermacher/MiLMMT-46-4B-v0.1-GGUF/resolve/main/MiLMMT-46-4B-v0.1.Q6_K.gguf",
                sha256: "2779a25cb1d55acfa4963af70e187fd022101ebb533006197cb12dc723d862cf",
                sizeBytes: 3_741_376_000
            ),
        ]
    )

    static let all: [ModelDownloadSpec] = [ruTOne, enZipformer, zhEnBilingual, m2m100418M, milmmt46B]

    private static let byModelId: [String: ModelDownloadSpec] = Dictionary(
        uniqueKeysWithValues: all.map { ($0.modelId, $0) }
    )

    static func forModelId(_ modelId: String) -> ModelDownloadSpec? {
        byModelId[modelId]
    }
}
