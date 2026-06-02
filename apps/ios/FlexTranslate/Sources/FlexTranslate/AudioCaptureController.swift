@preconcurrency import AVFoundation
import Foundation

// WS2: захват микрофона для offline ASR-пайплайна.
//
// Железо обычно отдаёт Float32 на родной частоте устройства (типа 48 кГц), а
// дальше по пайплайну ждут mono Int16 @ 16 кГц. Поэтому каждый буфер из tap-а
// прогоняем через AVAudioConverter и отдаём настоящий PCM в AudioFrame. Старый
// WS1-код PCM выбрасывал (отдавал пустые сэмплы) — здесь возвращаем как надо.
//
// Конкурентность (Swift 6 strict): жизненный цикл контроллера (start/stop/state)
// сидит на @MainActor, потому что им рулит main-actor-ный LiveSessionModel.
// Колбэк tap-а с render-потока трогать main-actor-ное состояние НЕ должен,
// поэтому контекст конвертации (converter + целевой формат) строим один раз в
// start() и захватываем *по значению* в @Sendable-замыкание — self не
// захватываем никогда. @preconcurrency import AVFoundation глушит диагностику
// по не-Sendable AVAudioPCMBuffer/AVAudioConverter — это документированный
// способ мостить старый API.
//
// ВАЖНО: этот путь конвертации компилируется и покрыт юнитами на уровне ring
// buffer/VAD, но сам живой захват через AVAudioEngine ещё надо проверить на
// устройстве, прежде чем доверять ему реальный распознаватель (отдельная фаза).
@MainActor
final class AudioCaptureController {
    private let engine = AVAudioEngine()

    // Целевой формат для пайплайна: mono Int16 @ 16 кГц, interleaved.
    private let targetSampleRate: Double
    private var isRunning = false

    init(targetSampleRate: Double = 16_000) {
        self.targetSampleRate = targetSampleRate
    }

    // Проверка разрешения чистая (только статика AVCaptureDevice), поэтому
    // оставляем nonisolated: можно await-ить, не таща main-actor-ное состояние
    // через границу изоляции.
    nonisolated func permissionState() async -> OfflineFirstState {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            return .readyOfflineAsr
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .audio)
            return granted ? .readyOfflineAsr : .captureBlocked(reason: "Microphone permission is required for offline ASR")
        default:
            return .captureBlocked(reason: "Microphone permission is required for offline ASR")
        }
    }

    var sampleRateHz: Int { Int(targetSampleRate) }

    // Запускает engine и отдаёт сконвертированные кадры mono Int16 @ 16 кГц.
    // Идемпотентно: повторный вызов на работающем engine ничего не делает.
    // Бросает при сбое запуска, чтобы вызывающий показал capture-blocked, а не
    // молча проглотил ошибку.
    func start(onFrame: @escaping @Sendable (AudioFrame) -> Void) throws {
        guard !isRunning else { return }
        installTap(onFrame: onFrame)
        try engine.start()
        isRunning = true
    }

    // Останавливает engine и снимает tap, чтобы следующий start() пересобрал
    // контекст конвертации под актуальный на тот момент формат железа.
    func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRunning = false
    }

    // Ставит tap на захват. Контекст конвертации собираем тут один раз (на main
    // actor) и захватываем по значению в замыкание render-потока — обратно в
    // self замыкание не лезет. Всё аккуратно: никаких force-unwrap, любой сбой
    // настройки или конвертации просто пропускает этот буфер.
    private func installTap(onFrame: @escaping @Sendable (AudioFrame) -> Void) {
        let input = engine.inputNode
        let inputFormat = input.inputFormat(forBus: 0)
        let context = PCMConversionContext(inputFormat: inputFormat, targetSampleRate: targetSampleRate)

        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { buffer, _ in
            let timestamp = Int64(ProcessInfo.processInfo.systemUptime * 1000)
            guard let context, let samples = context.convertToInt16(buffer), !samples.isEmpty else {
                return
            }
            onFrame(AudioFrame(pcm16: samples, sampleRateHz: context.targetSampleRateHz, monotonicTsMs: timestamp))
        }
    }
}

// Неизменяемый контекст конвертации, захватываемый по значению в tap render-потока.
// Хранит converter и целевой формат, собранные один раз при старте; строится на
// main actor, используется на аудиопотоке, но мутабельно нигде не шарится —
// поэтому его безопасно захватывать в @Sendable-замыкание (не-Sendable типы самой
// AVFoundation мостим через @preconcurrency).
// Одноразовый флаг «вход уже скормили» для input-блока AVAudioConverter.
// @unchecked Sendable тут честный: converter дёргает свой input-блок синхронно на
// вызывающем потоке всё время работы convert(to:error:), так что ссылку не трогают
// из двух потоков разом — но @Sendable-сигнатура блока всё равно требует Sendable-захватов.
private final class OneShotFlag: @unchecked Sendable {
    var consumed = false
}

private struct PCMConversionContext {
    let converter: AVAudioConverter
    let inputFormat: AVAudioFormat
    let targetFormat: AVAudioFormat
    let targetSampleRateHz: Int

    // Возвращает nil, если не собрался либо целевой формат mono Int16 @ 16 кГц,
    // либо converter — никаких force-unwrap на failable-инициализаторах.
    init?(inputFormat: AVAudioFormat, targetSampleRate: Double) {
        guard let target = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: targetSampleRate,
            channels: 1,
            interleaved: true
        ) else {
            return nil
        }
        guard let builtConverter = AVAudioConverter(from: inputFormat, to: target) else {
            return nil
        }
        self.converter = builtConverter
        self.inputFormat = inputFormat
        self.targetFormat = target
        self.targetSampleRateHz = Int(targetSampleRate)
    }

    // Конвертирует один буфер с железа (Float32 на частоте устройства) в mono Int16
    // @ 16 кГц и вытаскивает сэмплы из int16ChannelData результата. При любом сбое
    // возвращает nil, а не падает.
    func convertToInt16(_ buffer: AVAudioPCMBuffer) -> [Int16]? {
        // Размер выходного буфера прикидываем по соотношению частот (плюс запас).
        let ratio = targetFormat.sampleRate / inputFormat.sampleRate
        let estimatedFrames = Double(buffer.frameLength) * ratio
        let capacity = AVAudioFrameCount(estimatedFrames.rounded(.up)) + 1
        guard capacity > 0,
              let outputBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
            return nil
        }

        // AVAudioConverterInputBlock помечен @Sendable, поэтому захватить мутабельный
        // var нельзя (Swift 6 strict concurrency). Одноразовый флаг «уже скормили»
        // держим в reference-боксе — блок вызывается синхронно на этом же потоке,
        // так что обычный класс без блокировок безопасен.
        let fed = OneShotFlag()
        var conversionError: NSError?
        let status = converter.convert(to: outputBuffer, error: &conversionError) { _, inputStatus in
            // Скармливаем исходный буфер ровно один раз; дальше сигналим конец потока.
            if fed.consumed {
                inputStatus.pointee = .noDataNow
                return nil
            }
            fed.consumed = true
            inputStatus.pointee = .haveData
            return buffer
        }

        guard status != .error, conversionError == nil else { return nil }

        let frameCount = Int(outputBuffer.frameLength)
        guard frameCount > 0, let channelData = outputBuffer.int16ChannelData else { return nil }

        // Цель — mono interleaved, поэтому все сэмплы лежат в канале 0.
        let pointer = channelData[0]
        return Array(UnsafeBufferPointer(start: pointer, count: frameCount))
    }
}
