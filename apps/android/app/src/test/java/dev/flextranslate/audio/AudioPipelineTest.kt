package dev.flextranslate.audio

import dev.flextranslate.foundation.AsrProvider
import dev.flextranslate.foundation.AudioFrame
import dev.flextranslate.foundation.TranscriptEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic JVM tests for [AudioPipeline]: backpressure, ASR forwarding, observable state. */
class AudioPipelineTest {

    /** Records every forwarded frame and can be primed to return scripted transcripts. */
    private class RecordingAsrProvider(
        private val scripted: List<TranscriptEvent> = emptyList(),
    ) : AsrProvider {
        override val providerId = "recording-fake-asr"
        val received = mutableListOf<AudioFrame>()
        var resetCount = 0
            private set

        override fun accept(frame: AudioFrame): List<TranscriptEvent> {
            received.add(frame)
            return scripted
        }

        override fun reset() {
            resetCount += 1
        }
    }

    /** Trivial VAD stub that emits a SpeechStart on the Nth frame, otherwise stays silent. */
    private class ScriptedVad(private val startOnIndex: Int) : Vad {
        private var index = 0
        override fun accept(frame: AudioFrame): VadEvent? {
            val event = if (index == startOnIndex) {
                VadEvent.SpeechStart(monotonicTsMs = frame.monotonicTsMs)
            } else {
                null
            }
            index += 1
            return event
        }

        override fun reset() {
            index = 0
        }
    }

    private fun frame(tsMs: Long): AudioFrame =
        AudioFrame(pcm16 = ShortArray(320), sampleRateHz = 16_000, monotonicTsMs = tsMs)

    @Test
    fun `every frame is forwarded to the AsrProvider in order`() {
        val asr = RecordingAsrProvider()
        val pipeline = AudioPipeline(asrProvider = asr, vad = ScriptedVad(startOnIndex = -1))
        val frames = (0 until 5).map { frame(it * 20L) }
        frames.forEach(pipeline::accept)

        assertEquals(5, asr.received.size)
        assertEquals(frames.map { it.monotonicTsMs }, asr.received.map { it.monotonicTsMs })
    }

    @Test
    fun `ring buffer is bounded and drops oldest frames`() {
        val asr = RecordingAsrProvider()
        val pipeline = AudioPipeline(
            asrProvider = asr,
            vad = ScriptedVad(startOnIndex = -1),
            ringCapacity = 3,
        )
        // Feed 5 frames into a capacity-3 ring.
        (0 until 5).forEach { pipeline.accept(frame(it * 20L)) }

        assertEquals("Depth must never exceed capacity", 3, pipeline.bufferDepth)
        assertEquals("Two oldest frames dropped", 2L, pipeline.totalDroppedFrames)

        // The retained frames are the three most recent (oldest-first order).
        val retained = pipeline.drainBufferedFrames().map { it.monotonicTsMs }
        assertEquals(listOf(40L, 60L, 80L), retained)
        // ASR still saw all 5 frames — backpressure only bounds the buffer, not the stream.
        assertEquals(5, asr.received.size)
    }

    @Test
    fun `VadState is observable and updates on a SpeechStart event`() {
        val asr = RecordingAsrProvider()
        val snapshots = mutableListOf<AudioPipeline.Snapshot>()
        val pipeline = AudioPipeline(
            asrProvider = asr,
            vad = ScriptedVad(startOnIndex = 2),
            onUpdate = { snapshots.add(it) },
        )

        assertEquals(VadState.SILENCE, pipeline.currentVadState)
        (0 until 4).forEach { pipeline.accept(frame(it * 20L)) }

        assertEquals(VadState.SPEECH, pipeline.currentVadState)
        assertTrue(pipeline.latestVadEvent is VadEvent.SpeechStart)
        // Observer fired once per frame and the third snapshot reflects the transition.
        assertEquals(4, snapshots.size)
        assertEquals(VadState.SILENCE, snapshots[1].vadState)
        assertEquals(VadState.SPEECH, snapshots[2].vadState)
    }

    @Test
    fun `transcripts from the AsrProvider surface in the snapshot`() {
        val transcript = TranscriptEvent(text = "demo", isFinal = false, monotonicTsMs = 0L)
        val asr = RecordingAsrProvider(scripted = listOf(transcript))
        var lastSnapshot: AudioPipeline.Snapshot? = null
        val pipeline = AudioPipeline(
            asrProvider = asr,
            vad = ScriptedVad(startOnIndex = -1),
            onUpdate = { lastSnapshot = it },
        )

        pipeline.accept(frame(0L))

        val snapshot = lastSnapshot
        assertTrue(snapshot != null && snapshot.transcripts.size == 1)
        assertSame(transcript, snapshot?.transcripts?.first())
    }

    @Test
    fun `reset drains the buffer and resets VAD and ASR`() {
        val asr = RecordingAsrProvider()
        val pipeline = AudioPipeline(
            asrProvider = asr,
            vad = ScriptedVad(startOnIndex = 0),
            ringCapacity = 4,
        )
        (0 until 4).forEach { pipeline.accept(frame(it * 20L)) }
        assertEquals(VadState.SPEECH, pipeline.currentVadState)

        pipeline.reset()

        assertEquals(0, pipeline.bufferDepth)
        assertEquals(VadState.SILENCE, pipeline.currentVadState)
        assertEquals(0L, pipeline.totalDroppedFrames)
        assertEquals(1, asr.resetCount)
    }
}
