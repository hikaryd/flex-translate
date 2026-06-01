package dev.flextranslate.audio

import dev.flextranslate.foundation.AudioFrame

/**
 * Voice-activity detector contract. A [Vad] consumes one [AudioFrame] at a time and emits a
 * [VadEvent] only on a state transition (speech onset / offset); steady-state frames return null.
 *
 * The interface is the seam: the A1 default is [EnergyVad] (model-free, deterministic). The gated
 * G003/A2 swap is a real Silero VAD (sherpa-onnx) implementing this same interface.
 */
interface Vad {
    /** Feed one frame. Returns a transition event, or null when the [VadState] is unchanged. */
    fun accept(frame: AudioFrame): VadEvent?

    /** Reset to [VadState.SILENCE] and clear any debounce accumulators. */
    fun reset()
}

/** Two-state speech presence. */
enum class VadState {
    SILENCE,
    SPEECH,
}

/** Emitted on a speech-presence transition. Timestamps are monotonic (elapsedRealtime). */
sealed interface VadEvent {
    val monotonicTsMs: Long

    data class SpeechStart(override val monotonicTsMs: Long) : VadEvent

    data class SpeechEnd(override val monotonicTsMs: Long) : VadEvent
}
