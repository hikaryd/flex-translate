import Foundation

// Аудио-конвейер (WS2): стык между сырым захватом и слоем ASR/VAD.
//
// На каждый кадр: (1) кладёт его в кольцевой буфер с вытеснением старого и фикс-размером —
// чтобы медленный потребитель не разнёс память, (2) гоняет переданный VAD и запоминает
// последнее состояние/событие, (3) передаёт кадр в переданный `AsrProvider`. Сейчас ASR —
// это заглушка A1 (возвращает []), так что транскрипт не выдумывается — наружу идёт только
// реальное состояние VAD.
//
// Намеренно не потокобезопасен: тап захвата зовёт `accept` последовательно из своего
// колбэк-потока, а `LiveSessionModel` уводит апдейты состояния на @MainActor. Своих
// примитивов синхронизации у конвейера нет.
final class AudioPipeline {
    // Итог обработки одного кадра — чтобы вызывающий код и телеметрия могли среагировать
    // на переход VAD и на давление в буфере, не перечитывая состояние.
    struct FrameOutcome: Equatable {
        let vadEvent: VadEvent?
        let droppedOldest: Bool
    }

    private let vad: Vad
    private let asr: AsrProvider
    private let capacity: Int

    // Кольцевой буфер последних кадров с вытеснением старого. Ограничен `capacity`; когда
    // полон, перед добавлением нового выкидывается самый старый.
    private var ringBuffer: [AudioFrame] = []
    private var head = 0

    private(set) var vadState: VadState = .silence
    private(set) var latestEvent: VadEvent?

    // Последние события транскрипта от ASR (на этапе A1 пусто).
    private(set) var transcript: [TranscriptEvent] = []

    init(vad: Vad, asr: AsrProvider, capacity: Int = 64) {
        self.vad = vad
        self.asr = asr
        self.capacity = max(1, capacity)
        ringBuffer.reserveCapacity(self.capacity)
    }

    var bufferDepth: Int { ringBuffer.count }
    var speechActive: Bool { vadState == .speech }

    // Прогоняет один захваченный кадр через буфер -> VAD -> ASR. Возвращает итог
    // (переход VAD, выкинули ли старый кадр).
    @discardableResult
    func accept(_ frame: AudioFrame) -> FrameOutcome {
        let dropped = enqueue(frame)

        let event = vad.accept(frame)
        if let event {
            latestEvent = event
            switch event {
            case .speechStart:
                vadState = .speech
            case .speechEnd:
                vadState = .silence
            }
        }

        let events = asr.accept(frame: frame)
        if !events.isEmpty {
            transcript = events
        }

        return FrameOutcome(vadEvent: event, droppedOldest: dropped)
    }

    // Сброс буфера, VAD и ASR под новую сессию захвата.
    func reset() {
        ringBuffer.removeAll(keepingCapacity: true)
        head = 0
        vadState = .silence
        latestEvent = nil
        transcript = []
        vad.reset()
        asr.reset()
    }

    // Добавление с ограничением. Возвращает true, если ради места вытеснили самый старый
    // кадр (сигнал back-pressure). Двигаем head-индекс, чтобы в установившемся режиме
    // добавление оставалось O(1), когда буфер уже полон.
    private func enqueue(_ frame: AudioFrame) -> Bool {
        if ringBuffer.count < capacity {
            ringBuffer.append(frame)
            return false
        }
        ringBuffer[head] = frame
        head = (head + 1) % capacity
        return true
    }
}
