import Foundation

// Зеркалит Android MtModelSpec — раскладка файлов on-device MT-модели.
//
// Два варианта:
//   .seq2seqOnnx — многофайловый ONNX encoder+decoder (M2M-100, сбалансированный тир).
//   .gguf        — один файл GGUF через llama.cpp (MiLMMT, тир качества).
//
// Файлы лежат в <Application Support>/models/<modelId>/
// (Documents/models/<modelId>/ тоже проверяем — удобно закидывать через simctl).

enum MtModelSpec {
    case seq2seqOnnx(Seq2SeqOnnxConfig)
    case gguf(GgufConfig)

    struct Seq2SeqOnnxConfig {
        let modelId: String
        let encoder: String
        let decoderPrefill: String
        let decoderWithPast: String
        let tokenizer: String

        init(
            modelId: String,
            encoder: String,
            decoderPrefill: String,
            decoderWithPast: String,
            tokenizer: String = "tokenizer.json"
        ) {
            self.modelId = modelId
            self.encoder = encoder
            self.decoderPrefill = decoderPrefill
            self.decoderWithPast = decoderWithPast
            self.tokenizer = tokenizer
        }
    }

    struct GgufConfig {
        let modelId: String
        let gguf: String    // имя .gguf-файла внутри папки модели

        init(modelId: String, gguf: String) {
            self.modelId = modelId
            self.gguf = gguf
        }
    }

    var modelId: String {
        switch self {
        case .seq2seqOnnx(let c): return c.modelId
        case .gguf(let c): return c.modelId
        }
    }

    var requiredFiles: [String] {
        switch self {
        case .seq2seqOnnx(let c):
            return [c.encoder, c.decoderPrefill, c.decoderWithPast, c.tokenizer]
        case .gguf(let c):
            return [c.gguf]
        }
    }

    func isInstalled(in dir: URL) -> Bool {
        requiredFiles.allSatisfy { name in
            let url = dir.appendingPathComponent(name)
            let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
            return (attrs?[.size] as? Int ?? 0) > 0
        }
    }
}

// Конкретные спеки — зеркалит Android MtModelSpecs.
enum MtModelSpecs {
    // M2M-100 418M ONNX (quantized) — Xenova/m2m100_418M (MIT).
    // Декодер РАЗДЕЛЁН на пару prefill + with-past: так обходим слитую If-ноду,
    // которую ORT mobile не переваривает при нулевой длине prefill past_key_values.
    static let m2m100418M = MtModelSpec.seq2seqOnnx(.init(
        modelId: "m2m100-418m",
        encoder: "encoder_model_quantized.onnx",
        decoderPrefill: "decoder_model_quantized.onnx",
        decoderWithPast: "decoder_with_past_model_quantized.onnx"
    ))

    // MiLMMT-46-4B Q6_K GGUF — mradermacher/MiLMMT-46-4B-v0.1-GGUF (лицензия Gemma).
    // Один файл на ~3.74 ГБ; гоняем через llama.cpp xcframework (тир качества).
    static let milmmt46b4q6 = MtModelSpec.gguf(.init(
        modelId: "milmmt-46-4b-q6",
        gguf: "MiLMMT-46-4B-v0.1.Q6_K.gguf"
    ))

    static let all: [MtModelSpec] = [m2m100418M, milmmt46b4q6]
}
