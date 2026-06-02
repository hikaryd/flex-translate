package dev.flextranslate.audio

import dev.flextranslate.foundation.AudioFrame

/**
 * Контракт детектора голосовой активности. [Vad] принимает по одному [AudioFrame] и выдаёт
 * [VadEvent] только на переходе состояния (начало / конец речи); кадры без изменений дают null.
 *
 * Интерфейс — это шов: по умолчанию в A1 стоит [EnergyVad] (без модели, детерминированный). Закрытая
 * замена G003/A2 — настоящий Silero VAD (sherpa-onnx) с тем же интерфейсом.
 */
interface Vad {
    /** Подать один кадр. Возвращает событие перехода или null, если [VadState] не изменился. */
    fun accept(frame: AudioFrame): VadEvent?

    /** Сброс в [VadState.SILENCE] и очистка накопителей дебаунса. */
    fun reset()
}

/** Наличие речи в двух состояниях. */
enum class VadState {
    SILENCE,
    SPEECH,
}

/** Выдаётся на переходе наличия речи. Таймстемпы монотонные (elapsedRealtime). */
sealed interface VadEvent {
    val monotonicTsMs: Long

    data class SpeechStart(override val monotonicTsMs: Long) : VadEvent

    data class SpeechEnd(override val monotonicTsMs: Long) : VadEvent
}
