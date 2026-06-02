import Foundation
import SwiftUI

// View model экрана Эфир/Live.
//
// WS3 (A2): реальный стриминговый ASR sherpa-onnx подключается, когда для выбранного
// исходного языка лежат файлы модели. Файлов нет → остаётся заглушка, поддержку ASR
// не заявляем.
//
// G-DIALOGUE: каждая финализированная фраза ASR превращается в DialogueTurn, переводится
// на язык собеседника и добавляется в conversationLog. LiveView рисует лог как чат.
// Только реальный вывод — причины гейтинга показываем честно, текст не выдумываем.
@MainActor
final class LiveSessionModel: ObservableObject {
    // Источник/цель теперь FlexLanguage вместо прежних голых String.
    @Published private(set) var sourceLanguage: FlexLanguage = .ru
    @Published private(set) var targetLanguage: FlexLanguage = .en

    @Published private(set) var offlineState: OfflineFirstState = .cloudDisabled
    @Published private(set) var isCapturing = false

    // Только реальный вывод ASR, ничего не выдумываем. Пусто, когда провайдер вернул [].
    @Published private(set) var finalTranscript = ""
    @Published private(set) var partialTranscript = ""
    @Published private(set) var translation: TranslationResult?

    // Реальное состояние VAD, публикуем только во время захвата.
    @Published private(set) var vadState: VadState = .silence

    // Облако по умолчанию выключено.
    @Published private(set) var cloudActive = false

    // Состояние демо с тест-аудио (A2): заполняется по завершении runTestAudio().
    @Published private(set) var testAudioResult: String? = nil
    @Published private(set) var testAudioRunning = false

    // ---- Лог диалога (G-DIALOGUE) -----------------------------------------------

    /// Упорядоченный список финализированных реплик. Запись добавляется на main actor
    /// при финальном событии ASR; перевод подставляется асинхронно, когда отработает
    /// MT-воркер. @Published — чтобы LiveView перерисовывался на каждое добавление/обновление.
    @Published private(set) var conversationLog: [DialogueTurn] = []

    /// true, пока крутится MT-воркер по реплике (для спиннера на ожидающих репликах).
    @Published private(set) var translating = false

    // ---- Режим маршрутизации MT (G-AUTO-ROUTING) -------------------------------------------------------

    /// Куда направлять каждый запрос на перевод. По умолчанию AUTO.
    @Published private(set) var selectedRoutingMode: MtRoutingMode = .auto

    // ---- i18n -----------------------------------------------------------------------------------

    /// Активный каталог строк интерфейса. Composition root обновляет его при каждой смене языка,
    /// чтобы причины перевода всегда были на выбранном языке.
    var uiStrings: any Strings = StringsRu()

    // ---- Телеметрия ------------------------------------------------------------------------------

    /// Общий sink — заполняется один раз за сессию на старте захвата.
    let telemetrySink: TelemetrySink = .shared
    /// Контекст сессии; пересоздаётся на каждом старте захвата.
    private(set) var telemetryCtx: TelemetryContext = TelemetryContext.forDevice(
        appBuild: currentAppBuild(),
        sessionId: UUID().uuidString
    )

    // ---- Приватное -------------------------------------------------------------------------------

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
        // Тир QUALITY: MiLMMT-46-4B Q6_K через llama.cpp (приоритетный, если установлен).
        let milmmtSpec = MtModelSpecs.milmmt46b4q6
        let store = MtModelStore.shared
        if store.isInstalled(milmmtSpec),
           case .gguf(let cfg) = milmmtSpec {
            let dir = store.modelDir(for: milmmtSpec)
            return MilmmtMtProvider(spec: cfg, modelDir: dir)
        }
        // Откат на тир BALANCED: M2M-100 418M ONNX.
        let m2mSpec = MtModelSpecs.m2m100418M
        guard store.isInstalled(m2mSpec) else {
            return GatedTranslationProvider()
        }
        let dir = store.modelDir(for: m2mSpec)
        return M2m100MtProvider(spec: m2mSpec, modelDir: dir)
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

    // MARK: - Вычисляемые свойства

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

    // MARK: - Выбор языка

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

    // MARK: - Режим маршрутизации

    func selectRoutingMode(_ mode: MtRoutingMode) {
        guard mode != selectedRoutingMode else { return }
        selectedRoutingMode = mode
        translation = nil
        if !finalTranscript.isEmpty {
            translateFinal(finalTranscript)
        }
    }

    // MARK: - Разрешения

    func refreshPermission() async {
        let state = await capture.permissionState()
        offlineState = state
        translation = translator.translate(
            text: "",
            languagePair: "\(sourceLanguage.code)->\(targetLanguage.code)",
            deviceTier: telemetryCtx.deviceTier
        )
    }

    // MARK: - Захват

    func toggleCapture() {
        if isCapturing {
            stopIfNeeded()
        } else {
            guard canCapture else { return }
            // Новая сессия: свежий UUID и обновлённые поля контекста.
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
                    // Тап захвата срабатывает вне главного потока; прыгаем на main actor,
                    // чтобы непотокобезопасный pipeline и @Published-состояние трогались
                    // только из одного домена изоляции.
                    Task { @MainActor in
                        guard let self, self.isCapturing else { return }
                        let prevVad = self.pipeline.vadState
                        self.pipeline.accept(frame)
                        let newVad = self.pipeline.vadState
                        // Шлём события перехода VAD.
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

    // MARK: - Применение транскрипта

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

    // MARK: - Перевод (плоский — для legacy-поля translation)

    private func translateFinal(_ text: String) {
        let pair = "\(sourceLanguage.code)->\(targetLanguage.code)"
        let result = resolveAndTranslate(text: text, pair: pair)
        // Публикуем, только если транскрипт с тех пор не сдвинулся.
        if finalTranscript == text {
            translation = result
        }
    }

    // MARK: - Перевод реплики диалога

    private func translateTurn(
        _ turn: DialogueTurn,
        utteranceText: String,
        spokenLang: FlexLanguage,
        counterpartLang: FlexLanguage
    ) {
        let pair = "\(spokenLang.code)->\(counterpartLang.code)"
        translating = true
        let turnId = turn.id
        // Блокирующий инференс MT гоняем в отдельном фоновом потоке.
        // Не-Sendable провайдер прокидываем через Thread, чтобы все записи состояния
        // оставались в изоляции @MainActor.
        let utterance = utteranceText
        let providerRef = translator
        let sink = telemetrySink
        // Замораживаем снимок контекста для фонового потока.
        // TelemetryContext — Sendable; захват через let убирает предупреждение
        // «var захвачен в @Sendable-замыкании».
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

    /// Синхронный resolve-and-translate для legacy-поля плоского перевода.
    /// Идёт только через on-device провайдер (облачная маршрутизация — в более поздней WS).
    private func resolveAndTranslate(text: String, pair: String) -> TranslationResult {
        translator.translate(text: text, languagePair: pair, deviceTier: telemetryCtx.deviceTier)
    }

    // MARK: - Управление диалогом

    func clearDialogue() {
        conversationLog = []
        finalTranscript = ""
        partialTranscript = ""
        translation = nil
    }

    // MARK: - Хелперы

    private func otherLanguage(_ language: FlexLanguage) -> FlexLanguage {
        switch language {
        case .ru: return .en
        case .en: return .ru
        case .zh: return .ru
        }
    }

    // MARK: - Демо A2

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

// MARK: - Хелпер декодирования WAV (на уровне файла, зеркалит старую private-функцию)

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
