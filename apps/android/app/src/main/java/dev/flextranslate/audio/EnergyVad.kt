// Model-free A1 default VAD. Real Silero VAD (sherpa-onnx) is the gated G003/A2 swap behind this same Vad interface.
package dev.flextranslate.audio

import dev.flextranslate.foundation.AudioFrame
import kotlin.math.sqrt

/**
 * Deterministic RMS-energy voice-activity detector.
 *
 * A frame is "loud" when its RMS amplitude exceeds [rmsThreshold]. Raw loudness is debounced into
 * stable speech/silence transitions via hysteresis:
 *  - SILENCE → SPEECH requires loud audio sustained for at least [minSpeechDurationMs] (rejects
 *    short blips like a single noisy frame).
 *  - SPEECH → SILENCE requires quiet audio sustained for at least [minSilenceDurationMs] (the
 *    "hangover" — bridges natural inter-word gaps so speech is not chopped mid-utterance).
 *
 * No model, no allocation per frame, no device dependency — fully unit-testable on the JVM.
 */
class EnergyVad(
    private val rmsThreshold: Double = DEFAULT_RMS_THRESHOLD,
    private val minSpeechDurationMs: Long = DEFAULT_MIN_SPEECH_DURATION_MS,
    private val minSilenceDurationMs: Long = DEFAULT_MIN_SILENCE_DURATION_MS,
) : Vad {

    private var state: VadState = VadState.SILENCE

    /** Monotonic ts at which the current "candidate" (opposite-of-state) run began, or null. */
    private var candidateRunStartMs: Long? = null

    val currentState: VadState get() = state

    override fun accept(frame: AudioFrame): VadEvent? {
        val loud = rmsOf(frame.pcm16) >= rmsThreshold
        val now = frame.monotonicTsMs
        return when (state) {
            VadState.SILENCE -> evaluateSilence(loud, now)
            VadState.SPEECH -> evaluateSpeech(loud, now)
        }
    }

    override fun reset() {
        state = VadState.SILENCE
        candidateRunStartMs = null
    }

    /** While silent, a sustained loud run promotes to SPEECH and emits [VadEvent.SpeechStart]. */
    private fun evaluateSilence(loud: Boolean, now: Long): VadEvent? {
        if (!loud) {
            candidateRunStartMs = null
            return null
        }
        val runStart = candidateRunStartMs ?: now.also { candidateRunStartMs = it }
        if (now - runStart < minSpeechDurationMs) return null
        state = VadState.SPEECH
        candidateRunStartMs = null
        return VadEvent.SpeechStart(monotonicTsMs = now)
    }

    /** While speaking, a sustained quiet run (hangover) demotes to SILENCE and emits [VadEvent.SpeechEnd]. */
    private fun evaluateSpeech(loud: Boolean, now: Long): VadEvent? {
        if (loud) {
            candidateRunStartMs = null
            return null
        }
        val runStart = candidateRunStartMs ?: now.also { candidateRunStartMs = it }
        if (now - runStart < minSilenceDurationMs) return null
        state = VadState.SILENCE
        candidateRunStartMs = null
        return VadEvent.SpeechEnd(monotonicTsMs = now)
    }

    private fun rmsOf(pcm16: ShortArray): Double {
        if (pcm16.isEmpty()) return 0.0
        var sumSquares = 0.0
        for (sample in pcm16) {
            val value = sample.toDouble()
            sumSquares += value * value
        }
        return sqrt(sumSquares / pcm16.size)
    }

    companion object {
        /** ~1.4% of full-scale 16-bit — above quiet-room noise, below speech energy. */
        const val DEFAULT_RMS_THRESHOLD: Double = 450.0

        /** Speech must persist this long before onset is declared (blip rejection). */
        const val DEFAULT_MIN_SPEECH_DURATION_MS: Long = 120L

        /** Silence must persist this long before offset is declared (inter-word hangover). */
        const val DEFAULT_MIN_SILENCE_DURATION_MS: Long = 400L
    }
}
