import Foundation

// Real streaming offline ASR over the native sherpa-onnx runtime.
//
// A2 discipline: this produces GENUINE recogniser output — never fabricated text.
// If model files are absent the provider stays inert (returns []) and readiness()
// returns .missingOfflinePack so the UI gates honestly without crashing.
//
// Threading: accept/reset are called only from the single @MainActor pipeline
// path in LiveSessionModel (via AudioPipeline.accept). The recogniser is not
// shared across threads.
//
// Support-matrix claims still require WS6 benchmark evidence — a working A2
// demo is not a launch-support claim.
final class SherpaOnnxAsrProvider: AsrProvider {

    let spec: AsrModelSpec
    let modelDir: URL
    var providerId: String { "sherpa-onnx:\(spec.modelId)" }

    private enum State {
        case uninitialized
        case ready(SherpaOnnxRecognizer)
        case missing(packId: String)
        case failed(reason: String)
    }

    private var state: State = .uninitialized
    private var lastEmitted: String = ""

    init(spec: AsrModelSpec, modelDir: URL) {
        self.spec = spec
        self.modelDir = modelDir
    }

    // Honest readiness — used by LiveSessionModel to gate the UI.
    func readiness() -> OfflineFirstState {
        switch ensureInitialized() {
        case .ready: return .readyOfflineAsr
        case .missing(let packId): return .missingOfflinePack(packId: packId)
        case .failed: return .missingOfflinePack(packId: spec.modelId)
        case .uninitialized: return .missingOfflinePack(packId: spec.modelId)
        }
    }

    func accept(frame: AudioFrame) -> [TranscriptEvent] {
        guard case .ready(let recogniser) = ensureInitialized() else { return [] }
        let floats = toFloatSamples(frame.pcm16)
        recogniser.acceptWaveform(samples: floats, sampleRate: frame.sampleRateHz)
        while recogniser.isReady() { recogniser.decode() }
        let text = recogniser.getResult().text.trimmingCharacters(in: .whitespaces)
        let isEndpoint = recogniser.isEndpoint()
        return buildEvents(recogniser: recogniser, text: text, endpoint: isEndpoint, tsMs: frame.monotonicTsMs)
    }

    func reset() {
        if case .ready(let recogniser) = state {
            recogniser.reset()
        }
        lastEmitted = ""
    }

    // MARK: - Private

    private func ensureInitialized() -> State {
        if case .uninitialized = state {
            state = createState()
        }
        return state
    }

    private func createState() -> State {
        guard spec.isInstalled(in: modelDir) else {
            return .missing(packId: spec.modelId)
        }
        let cfg = buildConfig()
        let recogniser = SherpaOnnxRecognizer(config: &cfg.pointee)
        // SherpaOnnxRecognizer init never returns nil — it fatalErrors internally
        // if the config is structurally invalid, but absent files are caught above.
        return .ready(recogniser)
    }

    // Build the SherpaOnnxOnlineRecognizerConfig for this spec.
    // Uses the Swift helper functions vendored in SherpaOnnx.swift.
    private func buildConfig() -> UnsafeMutablePointer<SherpaOnnxOnlineRecognizerConfig> {
        let ptr = UnsafeMutablePointer<SherpaOnnxOnlineRecognizerConfig>.allocate(capacity: 1)
        let tokensPath = modelDir.appendingPathComponent(tokensFile()).path

        let modelConfig: SherpaOnnxOnlineModelConfig
        switch spec {
        case .toneCtc(let c):
            let modelPath = modelDir.appendingPathComponent(c.model).path
            modelConfig = sherpaOnnxOnlineModelConfig(
                tokens: tokensPath,
                numThreads: 2,
                toneCtc: sherpaOnnxOnlineToneCtcModelConfig(model: modelPath)
            )
        case .transducer(let c):
            let encoder = modelDir.appendingPathComponent(c.encoder).path
            let decoder = modelDir.appendingPathComponent(c.decoder).path
            let joiner  = modelDir.appendingPathComponent(c.joiner).path
            modelConfig = sherpaOnnxOnlineModelConfig(
                tokens: tokensPath,
                transducer: sherpaOnnxOnlineTransducerModelConfig(
                    encoder: encoder,
                    decoder: decoder,
                    joiner: joiner
                ),
                numThreads: 2,
                modelType: c.modelType
            )
        }

        let featConfig = sherpaOnnxFeatureConfig(sampleRate: 16_000, featureDim: 80)
        ptr.initialize(to: sherpaOnnxOnlineRecognizerConfig(
            featConfig: featConfig,
            modelConfig: modelConfig,
            enableEndpoint: true,
            rule1MinTrailingSilence: 2.4,
            rule2MinTrailingSilence: 1.2,
            rule3MinUtteranceLength: 30
        ))
        return ptr
    }

    private func tokensFile() -> String {
        switch spec {
        case .toneCtc(let c): return c.tokens
        case .transducer(let c): return c.tokens
        }
    }

    private func buildEvents(
        recogniser: SherpaOnnxRecognizer,
        text: String,
        endpoint: Bool,
        tsMs: Int64
    ) -> [TranscriptEvent] {
        var events: [TranscriptEvent] = []
        if endpoint {
            if !text.isEmpty {
                events.append(TranscriptEvent(text: text, isFinal: true, monotonicTsMs: tsMs))
            }
            recogniser.reset()
            lastEmitted = ""
        } else if !text.isEmpty && text != lastEmitted {
            lastEmitted = text
            events.append(TranscriptEvent(text: text, isFinal: false, monotonicTsMs: tsMs))
        }
        return events
    }

    // Int16 PCM → normalised Float in [-1, 1] as expected by sherpa-onnx.
    private func toFloatSamples(_ pcm16: [Int16]) -> [Float] {
        pcm16.map { Float($0) / 32_768.0 }
    }
}
