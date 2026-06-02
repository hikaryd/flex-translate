import Foundation

// Mirrors Android MtModelSpec — describes the on-device MT model file layout.
//
// The flagship spec is M2M100_418M: Xenova/m2m100_418M ONNX export (MIT).
// One model handles all four demo directions (RU↔EN, RU↔ZH) via a forced
// target-language BOS token — no English pivot.
//
// Files live in <Application Support>/models/<modelId>/
// (Documents/models/<modelId>/ is also checked for easy sideloading via simctl).

enum MtModelSpec {
    case seq2seqOnnx(Seq2SeqOnnxConfig)

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

    var modelId: String {
        switch self {
        case .seq2seqOnnx(let c): return c.modelId
        }
    }

    var requiredFiles: [String] {
        switch self {
        case .seq2seqOnnx(let c):
            return [c.encoder, c.decoderPrefill, c.decoderWithPast, c.tokenizer]
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

    static let all: [MtModelSpec] = [m2m100418M]
}
