package dev.flextranslate.audio

import dev.flextranslate.foundation.AsrProvider
import dev.flextranslate.foundation.AudioFrame
import dev.flextranslate.foundation.TranscriptEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Детерминированные JVM-тесты [AudioPipeline]: backpressure, проброс в ASR, наблюдаемое состояние. */
class AudioPipelineTest {

    /** Запоминает все пробрасываемые кадры; можно заранее задать, какие транскрипты возвращать. */
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

    /** Простейшая заглушка VAD: выдаёт SpeechStart на N-м кадре, в остальных случаях молчит. */
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
        // Скармливаем 5 кадров в кольцо ёмкостью 3.
        (0 until 5).forEach { pipeline.accept(frame(it * 20L)) }

        assertEquals("Depth must never exceed capacity", 3, pipeline.bufferDepth)
        assertEquals("Two oldest frames dropped", 2L, pipeline.totalDroppedFrames)

        // Остаются три самых свежих кадра (порядок от старого к новому).
        val retained = pipeline.drainBufferedFrames().map { it.monotonicTsMs }
        assertEquals(listOf(40L, 60L, 80L), retained)
        // ASR всё равно получил все 5 кадров — backpressure ограничивает буфер, а не поток.
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
        // Наблюдатель сработал по разу на кадр, переход видно в третьем снимке.
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
