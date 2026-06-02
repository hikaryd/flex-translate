import Foundation

// Mirrors Android MtModelSpec — describes the on-device MT model file layout.
//
// Two variants:
//   .seq2seqOnnx — multi-file ONNX encoder+decoder (M2M-100 balanced tier).
//   .gguf        — single-file GGUF run through llama.cpp (MiLMMT quality tier).
//
// Files live in <Application Support>/models/<modelId>/
// (Documents/models/<modelId>/ is also checked for easy sideloading via simctl).

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
        let gguf: String    // filename of the .gguf file inside the model directory

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

// Concrete specs — mirrors Android MtModelSpecs.
enum MtModelSpecs {
    // M2M-100 418M ONNX (quantized) — Xenova/m2m100_418M (MIT).
    // SPLIT decoder pair: prefill + with-past, avoids the merged If-node
    // that ORT mobile rejects at zero-length prefill past_key_values.
    static let m2m100418M = MtModelSpec.seq2seqOnnx(.init(
        modelId: "m2m100-418m",
        encoder: "encoder_model_quantized.onnx",
        decoderPrefill: "decoder_model_quantized.onnx",
        decoderWithPast: "decoder_with_past_model_quantized.onnx"
    ))

    // MiLMMT-46-4B Q6_K GGUF — mradermacher/MiLMMT-46-4B-v0.1-GGUF (Gemma license).
    // Single ~3.74 GB file; run through the llama.cpp xcframework (quality tier).
    static let milmmt46b4q6 = MtModelSpec.gguf(.init(
        modelId: "milmmt-46-4b-q6",
        gguf: "MiLMMT-46-4B-v0.1.Q6_K.gguf"
    ))

    static let all: [MtModelSpec] = [m2m100418M, milmmt46b4q6]
}
