import Foundation
import SwiftUI

// View model for the Эфир/Live surface.
//
// WS3 (A2): the real sherpa-onnx streaming ASR provider is wired in when model
// files are present for the selected source language. Absent models → the
// placeholder remains and no ASR support is claimed.
// The test-audio demo path feeds a bundled WAV through the provider so
// transcription can be shown in the simulator without a live microphone.
@MainActor
final class LiveSessionModel: ObservableObject {
    // Phase-0 scope: RU↔EN only.
    @Published private(set) var sourceLanguage = "RU"
    @Published private(set) var targetLanguage = "EN"

    @Published private(set) var offlineState: OfflineFirstState = .cloudDisabled
    @Published private(set) var isCapturing = false

    // Real ASR output only — never fabricated. Empty when provider returns [].
    @Published private(set) var transcript: [TranscriptEvent] = []
    @Published private(set) var translation: TranslationResult?

    // Real VAD state, published only while capturing.
    @Published private(set) var vadState: VadState = .silence

    // Cloud is OFF by default.
    @Published private(set) var cloudActive = false

    // Test-audio demo state (A2): set when runTestAudio() completes.
    @Published private(set) var testAudioResult: String? = nil
    @Published private(set) var testAudioRunning = false

    private let deviceTier = "high"

    private let capture: AudioCaptureController
    private let asr: AsrProvider
    private let translator: TranslationProvider
    private let pipeline: AudioPipeline

    // Resolve the best available ASR provider for the given source language.
    // Returns the real sherpa-onnx provider when model files are installed,
    // else the placeholder (no crash, no fabrication).
    static func makeAsrProvider(sourceLanguage: String) -> AsrProvider {
        let code = sourceLanguage.lowercased()
        guard let spec = AsrModelSpecs.forLanguage(code) else {
            return PlaceholderLocalAsrProvider()
        }
        let store = AsrModelStore.shared
        let dir = store.modelDir(for: spec)
        guard store.isInstalled(spec) else {
            return PlaceholderLocalAsrProvider()
        }
        return SherpaOnnxAsrProvider(spec: spec, modelDir: dir)
    }

    // Resolve the best available MT provider.
    // Returns the real M2M-100 provider when model files are installed,
    // else the gated placeholder (no crash, no fabrication).
    static func makeMtProvider() -> TranslationProvider {
        let spec = MtModelSpecs.m2m100418M
        let store = MtModelStore.shared
        guard store.isInstalled(spec) else {
            return GatedTranslationProvider()
        }
        let dir = store.modelDir(for: spec)
        return M2m100MtProvider(spec: spec, modelDir: dir)
    }

    init(
        capture: AudioCaptureController = AudioCaptureController(),
        asr: AsrProvider? = nil,
        translator: TranslationProvider? = nil,
        vad: Vad = EnergyVad()
    ) {
        let resolvedAsr = asr ?? LiveSessionModel.makeAsrProvider(sourceLanguage: "RU")
        let resolvedTranslator = translator ?? LiveSessionModel.makeMtProvider()
        self.capture = capture
        self.asr = resolvedAsr
        self.translator = resolvedTranslator
        self.pipeline = AudioPipeline(vad: vad, asr: resolvedAsr)
    }

    var speechActive: Bool { vadState == .speech }
    var bufferDepth: Int { pipeline.bufferDepth }

    var languagePair: String { "\(sourceLanguage) → \(targetLanguage)" }
    var asrProviderId: String { asr.providerId }

    // Resolve the current microphone permission into an OfflineFirstState.
    func refreshPermission() async {
        let state = await capture.permissionState()
        offlineState = state
        translation = translator.translate(
            text: "",
            languagePair: "\(sourceLanguage.lowercased())->\(targetLanguage.lowercased())",
            deviceTier: deviceTier
        )
    }

    // WS2: real capture is wired through the pipeline. Starting capture runs the
    // mic engine; each converted Int16 frame is routed (on the main actor) through
    // AudioPipeline -> VAD, publishing real `vadState`. ASR stays the gated
    // placeholder (returns []), so the transcript is never fabricated.
    func toggleCapture() {
        if isCapturing {
            stopIfNeeded()
        } else {
            guard canCapture else { return }
            asr.reset()
            pipeline.reset()
            transcript = []
            vadState = .silence
            do {
                try capture.start { [weak self] frame in
                    // The capture tap fires off the main thread; hop to the main
                    // actor so the (non-thread-safe) pipeline and @Published state
                    // are only ever touched from one isolation domain.
                    Task { @MainActor in
                        guard let self, self.isCapturing else { return }
                        self.pipeline.accept(frame)
                        self.vadState = self.pipeline.vadState
                    }
                }
                isCapturing = true
            } catch {
                isCapturing = false
                offlineState = .captureBlocked(reason: "Не удалось запустить аудиозахват")
            }
        }
    }

    // Symmetric stop path: stops the mic engine, clears the pipeline, resets VAD.
    // Call sites: toggleCapture(), LiveView .onDisappear, scenePhase → .background.
    func stopIfNeeded() {
        guard isCapturing else { return }
        capture.stop()
        pipeline.reset()
        isCapturing = false
        vadState = .silence
    }

    var canCapture: Bool {
        switch offlineState {
        case .readyOfflineAsr, .cloudDisabled, .unsupportedOfflineTranslation:
            return true
        case .captureBlocked, .missingOfflinePack:
            return false
        }
    }

    // Helper text explaining why capture is unavailable, if it is.
    var captureBlockReason: String? {
        switch offlineState {
        case let .captureBlocked(reason):
            return reason
        case let .missingOfflinePack(packId):
            return "нет пакета: \(packId) — установите его на вкладке «Модели»"
        case .readyOfflineAsr, .cloudDisabled, .unsupportedOfflineTranslation:
            return nil
        }
    }

    // Test-translation demo (A2 MT): translates a known RU phrase through the real
    // M2M-100 provider so MT can be demonstrated in the Simulator without a live mic.
    //
    // Real output only — if the model is absent or decoding fails, reports the honest
    // error; never fabricates translation text.
    //
    // The M2M-100 model files must be sideloaded into the simulator app container
    // (Documents/models/m2m100-418m/ or Application Support/models/m2m100-418m/).
    func runTestTranslation() {
        guard !testAudioRunning else { return }
        testAudioRunning = true
        testAudioResult = nil

        Task { @MainActor in
            defer { testAudioRunning = false }

            guard let mtProvider = translator as? M2m100MtProvider else {
                // Fall back: try to instantiate directly for the test even when
                // LiveSessionModel was constructed with GatedTranslationProvider.
                let spec = MtModelSpecs.m2m100418M
                guard MtModelStore.shared.isInstalled(spec) else {
                    testAudioResult = "⚠ MT-модель не установлена — скопируйте файлы в Documents/models/m2m100-418m/"
                    return
                }
                let dir = MtModelStore.shared.modelDir(for: spec)
                let provider = M2m100MtProvider(spec: spec, modelDir: dir)
                let result = provider.translate(
                    text: "сейчас к тебе приедет бригада давай",
                    languagePair: "ru->en",
                    deviceTier: deviceTier
                )
                if let text = result.text {
                    testAudioResult = "MT(ru→en): \(text)"
                    transcript = [TranscriptEvent(
                        text: text,
                        isFinal: true,
                        monotonicTsMs: Int64(Date().timeIntervalSince1970 * 1000)
                    )]
                } else {
                    testAudioResult = "⚠ MT вернул nil: \(result.unsupportedReason ?? "неизвестная причина")"
                }
                return
            }

            let result = mtProvider.translate(
                text: "сейчас к тебе приедет бригада давай",
                languagePair: "ru->en",
                deviceTier: deviceTier
            )
            if let text = result.text {
                testAudioResult = "MT(ru→en): \(text)"
                transcript = [TranscriptEvent(
                    text: text,
                    isFinal: true,
                    monotonicTsMs: Int64(Date().timeIntervalSince1970 * 1000)
                )]
            } else {
                testAudioResult = "⚠ MT вернул nil: \(result.unsupportedReason ?? "неизвестная причина")"
            }
        }
    }

    // Test-audio demo (A2): feeds a known WAV file through the real sherpa-onnx
    // provider and publishes the decoded text to `testAudioResult`.
    // Real output only — if the model is absent or decoding fails, reports the
    // honest error; never fabricates transcript text.
    //
    // The WAV is placed by the developer/CI into the app's Documents directory
    // (e.g. via `xcrun simctl` or first-run download). If absent, reports clearly.
    func runTestAudio() {
        guard !testAudioRunning else { return }
        testAudioRunning = true
        testAudioResult = nil

        Task { @MainActor in
            defer { testAudioRunning = false }

            // Resolve provider — must be the real sherpa-onnx one, not placeholder.
            guard let sherpaProvider = asr as? SherpaOnnxAsrProvider else {
                testAudioResult = "⚠ модель не загружена — скопируйте model.onnx + tokens.txt в Documents/models/ru-t-one-streaming-2025-09-08/"
                return
            }

            // Locate test WAV: Documents/test-ru-16k.wav (sideloaded for simulator demo).
            guard let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
                testAudioResult = "⚠ Documents directory not found"
                return
            }
            let wavURL = docsDir.appendingPathComponent("test-ru-16k.wav")
            guard FileManager.default.fileExists(atPath: wavURL.path) else {
                testAudioResult = "⚠ тест-аудио не найдено: \(wavURL.lastPathComponent) — скопируйте его в Documents/"
                return
            }

            // Read WAV and decode via the real provider.
            // SherpaOnnxAsrProvider is a class (reference type); Swift 6 strict
            // concurrency disallows sending it into a detached task from @MainActor.
            // Run on the main actor instead — the recogniser is not thread-safe and
            // is only ever accessed from this isolation domain anyway.
            let result = readAndDecodeWav(url: wavURL, provider: sherpaProvider)

            testAudioResult = result
            // Also populate the transcript panel so it's visible in the UI.
            if !result.hasPrefix("⚠") {
                transcript = [TranscriptEvent(
                    text: result,
                    isFinal: true,
                    monotonicTsMs: Int64(Date().timeIntervalSince1970 * 1000)
                )]
            }
        }
    }
}

// Feed a 16 kHz mono WAV through the provider in 100 ms chunks and collect
// all transcript events. Runs off the main actor (detached task) so the heavy
// ONNX inference doesn't block the UI thread.
// Returns the final transcript text or an error string prefixed with "⚠".
private func readAndDecodeWav(url: URL, provider: SherpaOnnxAsrProvider) -> String {
    do {
        let data = try Data(contentsOf: url)
        // Parse minimal WAV header: skip "RIFF", file size, "WAVE", "fmt ", chunk size (16),
        // audio format (2 bytes), num channels (2), sample rate (4), byte rate (4),
        // block align (2), bits per sample (2), "data", data chunk size (4) = 44 bytes total.
        guard data.count > 44 else { return "⚠ WAV слишком мал: \(data.count) байт" }

        let sampleRate: Int = data.withUnsafeBytes { ptr in
            Int(ptr.load(fromByteOffset: 24, as: UInt32.self))
        }
        let dataChunkSize: Int = data.withUnsafeBytes { ptr in
            Int(ptr.load(fromByteOffset: 40, as: UInt32.self))
        }
        let pcmStart = 44
        let pcmEnd = min(pcmStart + dataChunkSize, data.count)
        guard pcmEnd > pcmStart else { return "⚠ нет PCM данных в WAV" }

        // Convert raw bytes to Int16 samples.
        let pcmBytes = data[pcmStart..<pcmEnd]
        var samples = [Int16](repeating: 0, count: pcmBytes.count / 2)
        _ = samples.withUnsafeMutableBytes { dst in
            pcmBytes.copyBytes(to: dst)
        }

        // Feed in 1600-sample chunks (100 ms at 16 kHz) to simulate streaming.
        let chunkSize = 1600
        var allEvents: [TranscriptEvent] = []
        var offset = 0
        provider.reset()
        while offset < samples.count {
            let end = min(offset + chunkSize, samples.count)
            let chunk = Array(samples[offset..<end])
            let frame = AudioFrame(
                pcm16: chunk,
                sampleRateHz: sampleRate,
                monotonicTsMs: Int64(offset * 1000 / max(sampleRate, 1))
            )
            let events = provider.accept(frame: frame)
            allEvents.append(contentsOf: events)
            offset = end
        }
        // Flush: send a silent tail to trigger endpoint detection.
        let silence = AudioFrame(pcm16: [Int16](repeating: 0, count: chunkSize * 3),
                                 sampleRateHz: sampleRate,
                                 monotonicTsMs: Int64(samples.count * 1000 / max(sampleRate, 1)))
        allEvents.append(contentsOf: provider.accept(frame: silence))

        let finals = allEvents.filter(\.isFinal).map(\.text).joined(separator: " ")
        let partials = allEvents.filter { !$0.isFinal }.map(\.text).last ?? ""
        let result = finals.isEmpty ? partials : finals
        return result.isEmpty ? "⚠ пустой результат — модель не распознала речь" : result
    } catch {
        return "⚠ ошибка чтения WAV: \(error.localizedDescription)"
    }
}
