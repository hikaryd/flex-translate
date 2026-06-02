import Foundation

// Зеркалит sealed-интерфейс AsrModelSpec из Android.
// Два вида: ToneCtc (RU T-one: один model.onnx + tokens.txt)
// и Transducer (zipformer: encoder/decoder/joiner + tokens.txt).

enum AsrModelSpec {
    case toneCtc(ToneCtcConfig)
    case transducer(TransducerConfig)

    struct ToneCtcConfig {
        let modelId: String
        let model: String
        let tokens: String
        init(modelId: String, model: String = "model.onnx", tokens: String = "tokens.txt") {
            self.modelId = modelId
            self.model = model
            self.tokens = tokens
        }
    }

    struct TransducerConfig {
        let modelId: String
        let encoder: String
        let decoder: String
        let joiner: String
        let tokens: String
        let modelType: String
        init(
            modelId: String,
            encoder: String,
            decoder: String,
            joiner: String,
            tokens: String = "tokens.txt",
            modelType: String = "zipformer2"
        ) {
            self.modelId = modelId
            self.encoder = encoder
            self.decoder = decoder
            self.joiner = joiner
            self.tokens = tokens
            self.modelType = modelType
        }
    }

    var modelId: String {
        switch self {
        case .toneCtc(let c): return c.modelId
        case .transducer(let c): return c.modelId
        }
    }

    var requiredFiles: [String] {
        switch self {
        case .toneCtc(let c): return [c.model, c.tokens]
        case .transducer(let c): return [c.encoder, c.decoder, c.joiner, c.tokens]
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

// Реестр, повторяющий Android AsrModelSpecs.
enum AsrModelSpecs {
    static let ruTOne = AsrModelSpec.toneCtc(.init(modelId: "ru-t-one-streaming-2025-09-08"))

    static let enZipformer = AsrModelSpec.transducer(.init(
        modelId: "en-zipformer-mid-high-2023-06-26",
        encoder: "encoder.int8.onnx",
        decoder: "decoder.int8.onnx",
        joiner: "joiner.int8.onnx"
    ))

    static let zhEnBilingual = AsrModelSpec.transducer(.init(
        modelId: "zh-en-bilingual-zipformer-2023-02-20",
        encoder: "encoder.int8.onnx",
        decoder: "decoder.int8.onnx",
        joiner: "joiner.int8.onnx",
        modelType: "zipformer"
    ))

    static let all: [AsrModelSpec] = [ruTOne, enZipformer, zhEnBilingual]

    static func forLanguage(_ code: String) -> AsrModelSpec? {
        switch code.lowercased() {
        case "ru": return ruTOne
        case "en": return enZipformer
        case "zh": return zhEnBilingual
        default: return nil
        }
    }

    static func forCandidate(_ candidate: AsrCandidate) -> AsrModelSpec? {
        all.first { $0.modelId == candidate.id }
    }
}
