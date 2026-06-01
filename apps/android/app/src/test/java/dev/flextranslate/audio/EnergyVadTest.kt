package dev.flextranslate.audio

import dev.flextranslate.foundation.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for [EnergyVad]. Synthetic PCM only — no audio device, no model. Frames
 * carry explicit monotonic timestamps so debounce windows are reproducible.
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
        // 10 loud frames = 200ms > minSpeechDuration (120ms).
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
        // Onset.
        repeat(10) {
            vad.accept(loudFrame(ts))?.let { events.add(it) }
            ts += frameMs
        }
        // Offset: 25 silent frames = 500ms > minSilenceDuration (400ms).
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
        // One 20ms loud frame, well under the 120ms onset window.
        val blip = vad.accept(loudFrame(ts))
        assertNull("A single-frame blip must not trigger speech onset", blip)
        ts += frameMs
        // Followed by silence: still SILENCE, no events.
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
        // Onset.
        repeat(10) {
            vad.accept(loudFrame(ts))?.let { events.add(it) }
            ts += frameMs
        }
        // A 200ms quiet dip (< 400ms hangover) then loud again — must NOT emit SpeechEnd.
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
