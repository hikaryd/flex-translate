import Testing
@testable import FlexTranslate

// Unit coverage for the two pure, deterministic WS2 components: the energy VAD
// state machine and the bounded drop-oldest pipeline buffer. No audio hardware,
// no fabricated ASR — these assert real signal-processing behaviour only.

private func frame(amplitude: Int16, count: Int = 1024, ts: Int64 = 0) -> AudioFrame {
    AudioFrame(pcm16: Array(repeating: amplitude, count: count), sampleRateHz: 16_000, monotonicTsMs: ts)
}

@Suite("EnergyVad")
struct EnergyVadTests {
    @Test("Silence frames never trigger a speech-start event")
    func silenceStaysSilent() {
        let vad = EnergyVad()
        for _ in 0..<20 {
            #expect(vad.accept(frame(amplitude: 0)) == nil)
        }
    }

    @Test("Sustained loud frames confirm speech onset after the debounce window")
    func loudFramesStartSpeech() {
        let vad = EnergyVad(energyThreshold: 0.012, minSpeechFrames: 2, minSilenceFrames: 8)
        // Full-scale-ish amplitude is well above threshold.
        #expect(vad.accept(frame(amplitude: 16_000, ts: 1)) == nil) // 1 of 2
        let event = vad.accept(frame(amplitude: 16_000, ts: 2)) // 2 of 2 -> confirmed
        #expect(event == .speechStart(2))
    }

    @Test("Speech offset requires the full silence hangover")
    func quietFramesEndSpeechAfterHangover() {
        let vad = EnergyVad(energyThreshold: 0.012, minSpeechFrames: 1, minSilenceFrames: 3)
        #expect(vad.accept(frame(amplitude: 16_000, ts: 1)) == .speechStart(1))
        #expect(vad.accept(frame(amplitude: 0, ts: 2)) == nil) // 1 of 3
        #expect(vad.accept(frame(amplitude: 0, ts: 3)) == nil) // 2 of 3
        #expect(vad.accept(frame(amplitude: 0, ts: 4)) == .speechEnd(4)) // 3 of 3 -> confirmed
    }

    @Test("reset() clears accumulated state")
    func resetClearsState() {
        let vad = EnergyVad(minSpeechFrames: 1)
        #expect(vad.accept(frame(amplitude: 16_000)) == .speechStart(0))
        vad.reset()
        #expect(vad.currentState == .silence)
    }
}

@Suite("AudioPipeline")
struct AudioPipelineTests {
    @Test("Buffer depth is bounded by capacity (drop-oldest)")
    func boundedBuffer() {
        let pipeline = AudioPipeline(vad: EnergyVad(), asr: PlaceholderLocalAsrProvider(), capacity: 4)
        for i in 0..<10 {
            let outcome = pipeline.accept(frame(amplitude: 0, ts: Int64(i)))
            if i >= 4 {
                #expect(outcome.droppedOldest)
            }
        }
        #expect(pipeline.bufferDepth == 4)
    }

    @Test("Placeholder ASR never fabricates transcript events")
    func placeholderAsrEmitsNothing() {
        let pipeline = AudioPipeline(vad: EnergyVad(), asr: PlaceholderLocalAsrProvider())
        for i in 0..<5 {
            _ = pipeline.accept(frame(amplitude: 16_000, ts: Int64(i)))
        }
        #expect(pipeline.transcript.isEmpty)
    }

    @Test("VAD transition surfaces through the pipeline outcome")
    func vadTransitionSurfaces() {
        let pipeline = AudioPipeline(vad: EnergyVad(minSpeechFrames: 1), asr: PlaceholderLocalAsrProvider())
        let outcome = pipeline.accept(frame(amplitude: 16_000, ts: 7))
        #expect(outcome.vadEvent == .speechStart(7))
        #expect(pipeline.vadState == .speech)
    }

    @Test("reset() clears buffer and VAD state")
    func resetClears() {
        let pipeline = AudioPipeline(vad: EnergyVad(minSpeechFrames: 1), asr: PlaceholderLocalAsrProvider(), capacity: 8)
        _ = pipeline.accept(frame(amplitude: 16_000, ts: 1))
        pipeline.reset()
        #expect(pipeline.bufferDepth == 0)
        #expect(pipeline.vadState == .silence)
    }
}

@Suite("GatedTranslationProvider")
struct GatedTranslationProviderTests {
    @Test("Offline translation is always gated with an explicit reason — never fabricated")
    func gatedTranslationNeverFabricates() {
        let provider = GatedTranslationProvider()
        let result = provider.translate(text: "привет", languagePair: "ru->en", deviceTier: "high")
        #expect(result.text == nil)
        #expect(result.unsupportedReason != nil)
    }
}
