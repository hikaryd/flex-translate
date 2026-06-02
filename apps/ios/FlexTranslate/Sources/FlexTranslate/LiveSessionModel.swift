import Foundation
import SwiftUI

// View model for the Эфир/Live surface.
//
// WS3 (A2): the real sherpa-onnx streaming ASR provider is wired in when model
// files are present for the selected source language. Absent models → the
// placeholder remains and no ASR support is claimed.
//
// G-DIALOGUE: each finalized ASR utterance creates a DialogueTurn, is translated
// into the counterpart language, and appended to conversationLog. LiveView renders
// the log as a chat-style conversation. Real output only — gating reasons surface
// honestly, never fabricated text.
@MainActor
final class LiveSessionModel: ObservableObject {
    // FlexLanguage-typed source/target, replacing the old plain-String fields.
    @Published private(set) var sourceLanguage: FlexLanguage = .ru
    @Published private(set) var targetLanguage: FlexLanguage = .en

    @Published private(set) var offlineState: OfflineFirstState = .cloudDisabled
    @Published private(set) var isCapturing = false

    // Real ASR output only — never fabricated. Empty when provider returns [].
    @Published private(set) var finalTranscript = ""
    @Published private(set) var partialTranscript = ""
    @Published private(set) var translation: TranslationResult?

    // Real VAD state, published only while capturing.
    @Published private(set) var vadState: VadState = .silence

    // Cloud is OFF by default.
    @Published private(set) var cloudActive = false

    // Test-audio demo state (A2): set when runTestAudio() completes.
    @Published private(set) var testAudioResult: String? = nil
    @Published private(set) var testAudioRunning = false

    // ---- Dialogue conversation log (G-DIALOGUE) -----------------------------------------------

    /// Ordered list of finalized utterance turns. Each entry is appended on the main actor
    /// when an ASR final event fires; the translation slot is filled asynchronously once the
    /// MT worker completes. @Published so LiveView redraws on every append/update.
    @Published private(set) var conversationLog: [DialogueTurn] = []

    /// True while a per-turn MT worker is running (for visual spinner on pending turns).
    @Published private(set) var translating = false

    // ---- MT routing mode (G-AUTO-ROUTING) -------------------------------------------------------

    /// How to route each translation request. AUTO is the default.
    @Published private(set) var selectedRoutingMode: MtRoutingMode = .auto

    // ---- i18n -----------------------------------------------------------------------------------

    /// The active UI-chrome string catalog. Updated by the composition root on each language
    /// switch so that translation-reason strings are always in the selected language.
    var uiStrings: any Strings = StringsRu()

    // ---- Telemetry ------------------------------------------------------------------------------

    /// Shared sink — populated once per session at capture start.
    let telemetrySink: TelemetrySink = .shared
    /// Per-session context; recreated on each capture start.
    private(set) var telemetryCtx: TelemetryContext = TelemetryContext.forDevice(
        appBuild: currentAppBuild(),
        sessionId: UUID().uuidString
    )

    // ---- Private -------------------------------------------------------------------------------

    private let capture: AudioCaptureController
    private let asr: AsrProvider
    private let translator: TranslationProvider
    private let pipeline: AudioPipeline

    // MARK: - Init

    static func makeAsrProvider(sourceLanguage: FlexLanguage) -> AsrProvider {
        let code = sourceLanguage.code
        guard let spec = AsrModelSpecs.forLanguage(code) else {
            return PlaceholderLocalAsrProvider()
        }
        let store = AsrModelStore.shared
        guard store.isInstalled(spec) else {
            return PlaceholderLocalAsrProvider()
        }
        return SherpaOnnxAsrProvider(spec: spec, modelDir: store.modelDir(for: spec))
    }

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
        let resolvedAsr = asr ?? LiveSessionModel.makeAsrProvider(sourceLanguage: .ru)
        let resolvedTranslator = translator ?? LiveSessionModel.makeMtProvider()
        self.capture = capture
        self.asr = resolvedAsr
        self.translator = resolvedTranslator
        self.pipeline = AudioPipeline(vad: vad, asr: resolvedAsr)
    }

    // MARK: - Computed

    var speechActive: Bool { vadState == .speech }
    var bufferDepth: Int { pipeline.bufferDepth }
    var languagePair: String { "\(sourceLanguage.displayCode) → \(targetLanguage.displayCode)" }
    var asrProviderId: String { asr.providerId }

    private var telemetryLanguagePair: String {
        "\(sourceLanguage.code)->\(targetLanguage.code)"
    }

    var canCapture: Bool {
        switch offlineState {
        case .readyOfflineAsr, .cloudDisabled, .unsupportedOfflineTranslation:
            return true
        case .captureBlocked, .missingOfflinePack:
            return false
        }
    }

    var captureBlockReason: String? {
        switch offlineState {
        case let .captureBlocked(reason):
            return reason
        case let .missingOfflinePack(packId):
            return uiStrings.missingPackHint(packId)
        case .readyOfflineAsr, .cloudDisabled, .unsupportedOfflineTranslation:
            return nil
        }
    }

    // MARK: - Language selection

    func selectSource(_ language: FlexLanguage) {
        sourceLanguage = language
        if targetLanguage == language {
            targetLanguage = otherLanguage(language)
        }
    }

    func selectTarget(_ language: FlexLanguage) {
        targetLanguage = language
        if sourceLanguage == language {
            sourceLanguage = otherLanguage(language)
        }
    }

    func swapLanguages() {
        let prev = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = prev
    }

    // MARK: - Routing mode

    func selectRoutingMode(_ mode: MtRoutingMode) {
        guard mode != selectedRoutingMode else { return }
        selectedRoutingMode = mode
        translation = nil
        if !finalTranscript.isEmpty {
            translateFinal(finalTranscript)
        }
    }

    // MARK: - Permission

    func refreshPermission() async {
        let state = await capture.permissionState()
        offlineState = state
        translation = translator.translate(
            text: "",
            languagePair: "\(sourceLanguage.code)->\(targetLanguage.code)",
            deviceTier: telemetryCtx.deviceTier
        )
    }

    // MARK: - Capture

    func toggleCapture() {
        if isCapturing {
            stopIfNeeded()
        } else {
            guard canCapture else { return }
            // Fresh session: new UUID, updated context fields.
            telemetryCtx = TelemetryContext.forDevice(
                appBuild: currentAppBuild(),
                sessionId: UUID().uuidString
            )
            telemetryCtx.runtimeId = asr.providerId
            telemetryCtx.modelId = translator.providerId
            telemetryCtx.languagePair = telemetryLanguagePair

            telemetrySink.emitWith(ctx: telemetryCtx, eventType: TelemetrySink.evtSessionStart)

            asr.reset()
            pipeline.reset()
            finalTranscript = ""
            partialTranscript = ""
            translation = nil
            conversationLog = []
            vadState = .silence
            do {
                try capture.start { [weak self] frame in
                    // The capture tap fires off the main thread; hop to the main
                    // actor so the (non-thread-safe) pipeline and @Published state
                    // are only ever touched from one isolation domain.
                    Task { @MainActor in
                        guard let self, self.isCapturing else { return }
                        let prevVad = self.pipeline.vadState
                        self.pipeline.accept(frame)
                        let newVad = self.pipeline.vadState
                        // Emit VAD transition events.
                        if prevVad != newVad {
                            let evtType = newVad == .speech
                                ? TelemetrySink.evtVadSpeechStart
                                : TelemetrySink.evtVadSpeechEnd
                            self.telemetrySink.emitWith(ctx: self.telemetryCtx, eventType: evtType)
                        }
                        self.vadState = newVad
                        let events = self.pipeline.transcript
                        if !events.isEmpty {
                            self.applyTranscripts(events)
                        }
                    }
                }
                isCapturing = true
                telemetrySink.emitWith(ctx: telemetryCtx, eventType: TelemetrySink.evtCaptureStart)
            } catch {
                isCapturing = false
                offlineState = .captureBlocked(reason: uiStrings.mtEngineUnavailable("audio"))
            }
        }
    }

    func stopIfNeeded() {
        guard isCapturing else { return }
        telemetrySink.emitWith(ctx: telemetryCtx, eventType: TelemetrySink.evtCaptureStop)
        capture.stop()
        pipeline.reset()
        isCapturing = false
        vadState = .silence
    }

    // MARK: - Transcript application

    private func applyTranscripts(_ events: [TranscriptEvent]) {
        for event in events {
            if event.isFinal {
                telemetrySink.emitWith(ctx: telemetryCtx, eventType: TelemetrySink.evtAsrFinal,
                                       monotonicTsMs: event.monotonicTsMs)
                finalTranscript = [finalTranscript, event.text]
                    .filter { !$0.isEmpty }
                    .joined(separator: " ")
                partialTranscript = ""

                if !event.text.isEmpty {
                    let spokenLang = sourceLanguage
                    let counterpartLang = targetLanguage
                    let turn = DialogueTurn(
                        monotonicTs: event.monotonicTsMs,
                        spokenLanguage: spokenLang,
                        originalText: event.text,
                        translationLanguage: counterpartLang
                    )
                    conversationLog.append(turn)
                    translateFinal(finalTranscript)
                    translateTurn(turn, utteranceText: event.text,
                                  spokenLang: spokenLang, counterpartLang: counterpartLang)
                }
            } else {
                telemetrySink.emitWith(ctx: telemetryCtx, eventType: TelemetrySink.evtAsrPartial,
                                       monotonicTsMs: event.monotonicTsMs)
                partialTranscript = event.text
            }
        }
    }

    // MARK: - Translation (flat — for the legacy translation field)

    private func translateFinal(_ text: String) {
        let pair = "\(sourceLanguage.code)->\(targetLanguage.code)"
        let result = resolveAndTranslate(text: text, pair: pair)
        // Only publish if the transcript hasn't moved on.
        if finalTranscript == text {
            translation = result
        }
    }

    // MARK: - Dialogue turn translation

    private func translateTurn(
        _ turn: DialogueTurn,
        utteranceText: String,
        spokenLang: FlexLanguage,
        counterpartLang: FlexLanguage
    ) {
        let pair = "\(spokenLang.code)->\(counterpartLang.code)"
        translating = true
        let turnId = turn.id
        // Run the blocking MT inference on a detached background thread.
        // We bridge the non-Sendable provider via a Thread so we stay within
        // the @MainActor isolation for all state writes.
        let utterance = utteranceText
        let providerRef = translator
        let sink = telemetrySink
        // Build a frozen snapshot of the context for the background thread.
        // TelemetryContext is Sendable; capturing as let avoids the "var captured
        // in @Sendable closure" warning.
        var mutableCtx = telemetryCtx
        mutableCtx.languagePair = pair
        let frozenCtx: TelemetryContext = mutableCtx
        let mtStartTs = Int64(ProcessInfo.processInfo.systemUptime * 1000)
        sink.emitWith(ctx: frozenCtx, eventType: TelemetrySink.evtMtStart,
                      monotonicTsMs: mtStartTs)
        Thread.detachNewThread { [weak self] in
            let result = providerRef.translate(
                text: utterance,
                languagePair: pair,
                deviceTier: frozenCtx.deviceTier
            )
            let mtEndTs = Int64(ProcessInfo.processInfo.systemUptime * 1000)
            let latencyMs = mtEndTs - mtStartTs
            sink.emitWith(ctx: frozenCtx, eventType: TelemetrySink.evtMtEnd,
                          monotonicTsMs: mtEndTs,
                          payload: ["latency_ms": String(latencyMs),
                                    "provider": frozenCtx.modelId])
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.updateTurnResult(
                    turnId: turnId,
                    text: result.text,
                    reason: result.unsupportedReason,
                    engineLabel: nil
                )
                self.translating = false
            }
        }
    }

    private func updateTurnResult(turnId: String, text: String?, reason: String?, engineLabel: String?) {
        guard let index = conversationLog.firstIndex(where: { $0.id == turnId }) else { return }
        conversationLog[index] = conversationLog[index].withTranslation(
            text: text, reason: reason, engineLabel: engineLabel
        )
    }

    /// Synchronous resolve-and-translate used for the legacy flat translation field.
    /// Routes through the on-device provider only (cloud routing is deferred to a later WS).
    private func resolveAndTranslate(text: String, pair: String) -> TranslationResult {
        translator.translate(text: text, languagePair: pair, deviceTier: telemetryCtx.deviceTier)
    }

    // MARK: - Dialogue control

    func clearDialogue() {
        conversationLog = []
        finalTranscript = ""
        partialTranscript = ""
        translation = nil
    }

    // MARK: - Helpers

    private func otherLanguage(_ language: FlexLanguage) -> FlexLanguage {
        switch language {
        case .ru: return .en
        case .en: return .ru
        case .zh: return .ru
        }
    }

    // MARK: - A2 demos

    func runTestTranslation() {
        guard !testAudioRunning else { return }
        testAudioRunning = true
        testAudioResult = nil

        Task { @MainActor in
            defer { testAudioRunning = false }

            guard let mtProvider = translator as? M2m100MtProvider else {
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
                    deviceTier: telemetryCtx.deviceTier
                )
                if let text = result.text {
                    testAudioResult = "MT(ru→en): \(text)"
                    let ts = Int64(Date().timeIntervalSince1970 * 1000)
                    let turn = DialogueTurn(
                        monotonicTs: ts,
                        spokenLanguage: .ru,
                        originalText: "сейчас к тебе приедет бригада давай",
                        translatedText: text,
                        translationLanguage: .en,
                        mtEngineUsed: "m2m100-418m"
                    )
                    conversationLog.append(turn)
                } else {
                    testAudioResult = "⚠ MT вернул nil: \(result.unsupportedReason ?? "неизвестная причина")"
                }
                return
            }

            let result = mtProvider.translate(
                text: "сейчас к тебе приедет бригада давай",
                languagePair: "ru->en",
                deviceTier: telemetryCtx.deviceTier
            )
            if let text = result.text {
                testAudioResult = "MT(ru→en): \(text)"
                let ts = Int64(Date().timeIntervalSince1970 * 1000)
                let turn = DialogueTurn(
                    monotonicTs: ts,
                    spokenLanguage: .ru,
                    originalText: "сейчас к тебе приедет бригада давай",
                    translatedText: text,
                    translationLanguage: .en,
                    mtEngineUsed: "m2m100-418m"
                )
                conversationLog.append(turn)
            } else {
                testAudioResult = "⚠ MT вернул nil: \(result.unsupportedReason ?? "неизвестная причина")"
            }
        }
    }

    func runTestAudio() {
        guard !testAudioRunning else { return }
        testAudioRunning = true
        testAudioResult = nil

        Task { @MainActor in
            defer { testAudioRunning = false }

            guard let sherpaProvider = asr as? SherpaOnnxAsrProvider else {
                testAudioResult = "⚠ модель не загружена — скопируйте model.onnx + tokens.txt в Documents/models/ru-t-one-streaming-2025-09-08/"
                return
            }

            guard let docsDir = FileManager.default.urls(
                for: .documentDirectory, in: .userDomainMask
            ).first else {
                testAudioResult = "⚠ Documents directory not found"
                return
            }
            let wavURL = docsDir.appendingPathComponent("test-ru-16k.wav")
            guard FileManager.default.fileExists(atPath: wavURL.path) else {
                testAudioResult = "⚠ тест-аудио не найдено: \(wavURL.lastPathComponent) — скопируйте его в Documents/"
                return
            }

            let result = readAndDecodeWav(url: wavURL, provider: sherpaProvider)
            testAudioResult = result
            if !result.hasPrefix("⚠") {
                let ts = Int64(Date().timeIntervalSince1970 * 1000)
                let turn = DialogueTurn(
                    monotonicTs: ts,
                    spokenLanguage: sourceLanguage,
                    originalText: result,
                    translationLanguage: targetLanguage
                )
                conversationLog.append(turn)
                translateTurn(turn, utteranceText: result,
                              spokenLang: sourceLanguage, counterpartLang: targetLanguage)
            }
        }
    }
}

// MARK: - WAV decode helper (file-scope, mirrors old private func)

private func readAndDecodeWav(url: URL, provider: SherpaOnnxAsrProvider) -> String {
    do {
        let data = try Data(contentsOf: url)
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

        let pcmBytes = data[pcmStart..<pcmEnd]
        var samples = [Int16](repeating: 0, count: pcmBytes.count / 2)
        _ = samples.withUnsafeMutableBytes { dst in
            pcmBytes.copyBytes(to: dst)
        }

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
            allEvents.append(contentsOf: provider.accept(frame: frame))
            offset = end
        }
        let silence = AudioFrame(
            pcm16: [Int16](repeating: 0, count: chunkSize * 3),
            sampleRateHz: sampleRate,
            monotonicTsMs: Int64(samples.count * 1000 / max(sampleRate, 1))
        )
        allEvents.append(contentsOf: provider.accept(frame: silence))

        let finals = allEvents.filter(\.isFinal).map(\.text).joined(separator: " ")
        let partials = allEvents.filter { !$0.isFinal }.map(\.text).last ?? ""
        let result = finals.isEmpty ? partials : finals
        return result.isEmpty ? "⚠ пустой результат — модель не распознала речь" : result
    } catch {
        return "⚠ ошибка чтения WAV: \(error.localizedDescription)"
    }
}
