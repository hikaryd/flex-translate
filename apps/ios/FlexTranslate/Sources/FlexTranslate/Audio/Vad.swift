import Foundation

// Контракт VAD (детекция речи) для аудио-конвейера WS2.
//
// `Vad` ест моно Int16 PCM-кадры (см. `AudioFrame`) и отдаёт событие перехода
// только когда речь началась или закончилась — никогда не по кадру. Поэтому
// вызывать его на потоке захвата дёшево, а UI цепляется к дискретным
// телеметрийным событиям `vad_speech_start` / `vad_speech_end`.
protocol Vad {
    // Скармливаем один кадр. Вернёт событие перехода, только если на этом кадре
    // состояние речи перевернулось, иначе nil.
    func accept(_ frame: AudioFrame) -> VadEvent?

    // Сбросить всё накопленное (счётчики hangover, текущее состояние). Зовём при
    // старте/стопе захвата, чтобы новая сессия не унаследовала устаревший флаг речи.
    func reset()
}

// Переход через границу речи. В associated value — монотонный таймстемп кадра (мс),
// чтобы телеметрия в WS6 могла привязать событие ко времени. Имена ложатся 1:1 на
// значения `event_type` телеметрии `vad_speech_start`/`vad_speech_end`.
enum VadEvent: Equatable {
    case speechStart(Int64)
    case speechEnd(Int64)
}

// Текущее состояние детектора. `silence` — покой до первой речи и после
// подтверждённого конца речи; `speech` держится, пока есть голос.
enum VadState: Equatable, CustomStringConvertible {
    case silence
    case speech

    var description: String {
        switch self {
        case .silence: return "silence"
        case .speech: return "speech"
        }
    }
}
