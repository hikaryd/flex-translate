package dev.flextranslate.audio

import dev.flextranslate.foundation.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Детерминированные JVM-тесты для [EnergyVad]. Только синтетический PCM — без аудиоустройства и без
 * модели. У фреймов явные монотонные таймстемпы, чтобы окна дебаунса были воспроизводимы.
 */
class EnergyVadTest {

    private val sampleRate = 16_000
    private val frameMs = 20L

    private fun silenceFrame(tsMs: Long): AudioFrame =
        AudioFrame(pcm16 = ShortArray(320), sampleRateHz = sampleRate, monotonicTsMs = tsMs)

    private fun loudFrame(tsMs: Long, amplitude: Short = 8_000): AudioFrame {
        val pcm = ShortArray(320) { amplitude }
        return AudioFrame(pcm16 = pcm, sampleRateHz = sampleRate, monotonicTsMs = tsMs)
    }

    @Test
    fun `pure silence never emits SpeechStart and stays SILENCE`() {
        val vad = EnergyVad()
        var ts = 0L
        repeat(50) {
            val event = vad.accept(silenceFrame(ts))
            assertNull("Silence must not produce a VAD event", event)
            ts += frameMs
        }
        assertEquals(VadState.SILENCE, vad.currentState)
    }

    @Test
    fun `sustained loud audio past min-speech-duration emits SpeechStart`() {
        val vad = EnergyVad()
        val events = mutableListOf<VadEvent>()
        var ts = 0L
        // 10 громких фреймов = 200мс > minSpeechDuration (120мс).
        repeat(10) {
            vad.accept(loudFrame(ts))?.let { events.add(it) }
            ts += frameMs
        }
        assertEquals(1, events.size)
        assertTrue("Expected SpeechStart", events.first() is VadEvent.SpeechStart)
        assertEquals(VadState.SPEECH, vad.currentState)
    }

    @Test
    fun `speech then silence past hangover emits SpeechEnd`() {
        val vad = EnergyVad()
        val events = mutableListOf<VadEvent>()
        var ts = 0L
        // Начало речи.
        repeat(10) {
            vad.accept(loudFrame(ts))?.let { events.add(it) }
            ts += frameMs
        }
        // Конец речи: 25 тихих фреймов = 500мс > minSilenceDuration (400мс).
        repeat(25) {
            vad.accept(silenceFrame(ts))?.let { events.add(it) }
            ts += frameMs
        }
        assertEquals(2, events.size)
        assertTrue("First event SpeechStart", events[0] is VadEvent.SpeechStart)
        assertTrue("Second event SpeechEnd", events[1] is VadEvent.SpeechEnd)
        assertEquals(VadState.SILENCE, vad.currentState)
    }

    @Test
    fun `single loud blip below min-speech-duration does not trigger SpeechStart`() {
        val vad = EnergyVad()
        var ts = 0L
        // Один громкий фрейм на 20мс — намного меньше окна старта в 120мс.
        val blip = vad.accept(loudFrame(ts))
        assertNull("A single-frame blip must not trigger speech onset", blip)
        ts += frameMs
        // Дальше тишина: остаёмся в SILENCE, событий нет.
        repeat(10) {
            assertNull(vad.accept(silenceFrame(ts)))
            ts += frameMs
        }
        assertEquals(VadState.SILENCE, vad.currentState)
    }

    @Test
    fun `brief silence dip inside speech does not prematurely end speech`() {
        val vad = EnergyVad()
        val events = mutableListOf<VadEvent>()
        var ts = 0L
        // Начало речи.
        repeat(10) {
            vad.accept(loudFrame(ts))?.let { events.add(it) }
            ts += frameMs
        }
        // Провал в тишину на 200мс (< 400мс hangover), потом снова громко — SpeechEnd НЕ должен сработать.
        repeat(10) {
            vad.accept(silenceFrame(ts))?.let { events.add(it) }
            ts += frameMs
        }
        repeat(5) {
            vad.accept(loudFrame(ts))?.let { events.add(it) }
            ts += frameMs
        }
        assertEquals(1, events.size)
        assertTrue(events.first() is VadEvent.SpeechStart)
        assertEquals(VadState.SPEECH, vad.currentState)
    }

    @Test
    fun `reset returns detector to SILENCE`() {
        val vad = EnergyVad()
        var ts = 0L
        repeat(10) {
            vad.accept(loudFrame(ts))
            ts += frameMs
        }
        assertEquals(VadState.SPEECH, vad.currentState)
        vad.reset()
        assertEquals(VadState.SILENCE, vad.currentState)
    }
}
