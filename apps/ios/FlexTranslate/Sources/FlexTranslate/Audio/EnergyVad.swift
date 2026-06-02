import Foundation

// VAD по умолчанию для A1 — без модели; настоящий Silero VAD (sherpa-onnx) подменяется
// за этим протоколом на этапе A2.
//
// Идея: считаем RMS-энергию кадра, нормируем в 0...1 относительно полной шкалы Int16
// и сравниваем с `energyThreshold`. Простой автомат на два состояния с дебаунсом-hangover
// не дёргается на паузах между словами и коротких всплесках:
//   - silence -> speech: нужно `minSpeechFrames` громких кадров подряд
//   - speech -> silence: нужно `minSilenceFrames` тихих кадров подряд
// VadEvent отдаём только на подтверждённом переходе.
final class EnergyVad: Vad {
    // Порог RMS (нормированный 0...1), выше которого кадр считается "громким".
    private let energyThreshold: Float
    // Сколько громких кадров подряд нужно, чтобы подтвердить начало речи.
    private let minSpeechFrames: Int
    // Сколько тихих кадров подряд нужно, чтобы подтвердить конец речи (hangover).
    private let minSilenceFrames: Int

    private var state: VadState = .silence
    // Счётчик кадров подряд, противоположных текущему состоянию — копим улики
    // к следующему переходу.
    private var transitionFrames = 0

    // Дефолты под 16 кГц / кадры ~1024 сэмпла (~64 мс). Порог консервативный,
    // дебаунс в несколько кадров перекрывает естественные паузы между словами.
    init(
        energyThreshold: Float = 0.012,
        minSpeechFrames: Int = 2,
        minSilenceFrames: Int = 8
    ) {
        self.energyThreshold = energyThreshold
        self.minSpeechFrames = max(1, minSpeechFrames)
        self.minSilenceFrames = max(1, minSilenceFrames)
    }

    var currentState: VadState { state }

    func accept(_ frame: AudioFrame) -> VadEvent? {
        let loud = Self.rms(of: frame.pcm16) >= energyThreshold

        switch state {
        case .silence:
            guard loud else {
                transitionFrames = 0
                return nil
            }
            transitionFrames += 1
            guard transitionFrames >= minSpeechFrames else { return nil }
            state = .speech
            transitionFrames = 0
            return .speechStart(frame.monotonicTsMs)

        case .speech:
            guard !loud else {
                transitionFrames = 0
                return nil
            }
            transitionFrames += 1
            guard transitionFrames >= minSilenceFrames else { return nil }
            state = .silence
            transitionFrames = 0
            return .speechEnd(frame.monotonicTsMs)
        }
    }

    func reset() {
        state = .silence
        transitionFrames = 0
    }

    // Полная шкала Int16. Берём 32768 (а не Int16.max=32767), чтобы максимально
    // отрицательный сэмпл (-32768) уложился в нормированный диапазон [-1, 1].
    private static let int16FullScale = 32768.0

    // RMS-амплитуда, нормированная по полной шкале Int16.
    // Для пустого кадра возвращает 0 (нет сэмплов -> считаем тишиной).
    private static func rms(of samples: [Int16]) -> Float {
        guard !samples.isEmpty else { return 0 }
        var sumSquares: Double = 0
        for sample in samples {
            let normalised = Double(sample) / int16FullScale
            sumSquares += normalised * normalised
        }
        let meanSquare = sumSquares / Double(samples.count)
        return Float(meanSquare.squareRoot())
    }
}
