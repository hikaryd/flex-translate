// VAD по умолчанию для A1 — без модели. Настоящий Silero VAD (sherpa-onnx) — это гейченный свап G003/A2 за тем же интерфейсом Vad.
package dev.flextranslate.audio

import dev.flextranslate.foundation.AudioFrame
import kotlin.math.sqrt

/**
 * Детерминированный VAD по RMS-энергии.
 *
 * Кадр считаем «громким», когда его RMS-амплитуда выше [rmsThreshold]. Сырую громкость через
 * гистерезис превращаем в устойчивые переходы речь/тишина:
 *  - SILENCE → SPEECH требует громкого звука подряд минимум [minSpeechDurationMs] (отсекает
 *    короткие всплески вроде одного шумного кадра).
 *  - SPEECH → SILENCE требует тишины подряд минимум [minSilenceDurationMs] («hangover» —
 *    перекрывает естественные паузы между словами, чтобы речь не рубилась посреди фразы).
 *
 * Без модели, без аллокаций на кадр, без зависимости от устройства — полностью тестируется на JVM.
 */
class EnergyVad(
    private val rmsThreshold: Double = DEFAULT_RMS_THRESHOLD,
    private val minSpeechDurationMs: Long = DEFAULT_MIN_SPEECH_DURATION_MS,
    private val minSilenceDurationMs: Long = DEFAULT_MIN_SILENCE_DURATION_MS,
) : Vad {

    private var state: VadState = VadState.SILENCE

    /** Монотонная метка времени начала текущего «кандидатного» (противоположного состоянию) прогона, или null. */
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

    /** В тишине устойчивый громкий прогон переводит в SPEECH и шлёт [VadEvent.SpeechStart]. */
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

    /** Во время речи устойчивая тишина (hangover) переводит в SILENCE и шлёт [VadEvent.SpeechEnd]. */
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
        /** ~1.4% от полной шкалы 16 бит — выше шума тихой комнаты, ниже энергии речи. */
        const val DEFAULT_RMS_THRESHOLD: Double = 450.0

        /** Речь должна держаться столько, прежде чем объявим начало (отсев всплесков). */
        const val DEFAULT_MIN_SPEECH_DURATION_MS: Long = 120L

        /** Тишина должна держаться столько, прежде чем объявим конец (hangover между словами). */
        const val DEFAULT_MIN_SILENCE_DURATION_MS: Long = 400L
    }
}
