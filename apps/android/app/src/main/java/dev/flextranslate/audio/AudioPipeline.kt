package dev.flextranslate.audio

import dev.flextranslate.foundation.AsrProvider
import dev.flextranslate.foundation.AudioFrame
import dev.flextranslate.foundation.TranscriptEvent
import java.util.ArrayDeque

/**
 * Glue between raw mic frames and the recognition stack.
 *
 * For every accepted [AudioFrame] the pipeline:
 *  1. forwards the frame to the injected [AsrProvider] and collects any [TranscriptEvent]s,
 *  2. enqueues the frame into a bounded ring buffer with drop-oldest backpressure (a slow/blocked
 *     consumer never grows memory unbounded — the oldest frame is dropped instead),
 *  3. runs the injected [Vad] and records the latest [VadState] + [VadEvent],
 *  4. publishes any [TranscriptEvent]s and a state snapshot to the optional [onUpdate] observer.
 *
 * Threading: [accept] is expected to be driven by a SINGLE capture thread (the recognizer holds its
 * own stream state and is not re-entrant), so the [AsrProvider.accept] call in step 1 runs OUTSIDE
 * [lock] — the lock guards only the shared VAD/ring/snapshot fields so a concurrent reader (e.g.
 * [currentVadState] from the UI thread) observes a consistent snapshot. The observer is likewise
 * invoked outside the lock to avoid re-entrancy deadlocks.
 */
class AudioPipeline(
    private val asrProvider: AsrProvider,
    private val vad: Vad,
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
    private val onUpdate: (Snapshot) -> Unit = {},
) {

    /** Immutable observable view of the pipeline after a frame is processed. */
    data class Snapshot(
        val vadState: VadState,
        val latestEvent: VadEvent?,
        val transcripts: List<TranscriptEvent>,
        val bufferDepth: Int,
        val droppedFrames: Long,
    )

    private val lock = Any()
    private val ring = ArrayDeque<AudioFrame>(ringCapacity)
    private var vadState: VadState = VadState.SILENCE
    private var latestEvent: VadEvent? = null
    private var droppedFrames: Long = 0L

    init {
        require(ringCapacity > 0) { "ringCapacity must be positive, was $ringCapacity" }
    }

    val currentVadState: VadState get() = synchronized(lock) { vadState }
    val latestVadEvent: VadEvent? get() = synchronized(lock) { latestEvent }
    val bufferDepth: Int get() = synchronized(lock) { ring.size }
    val totalDroppedFrames: Long get() = synchronized(lock) { droppedFrames }

    /** Process one captured frame. Safe to call from the capture thread. */
    fun accept(frame: AudioFrame) {
        val transcripts = asrProvider.accept(frame)
        val snapshot = synchronized(lock) {
            enqueueDropOldest(frame)
            val event = vad.accept(frame)
            if (event != null) {
                latestEvent = event
                vadState = when (event) {
                    is VadEvent.SpeechStart -> VadState.SPEECH
                    is VadEvent.SpeechEnd -> VadState.SILENCE
                }
            }
            Snapshot(
                vadState = vadState,
                latestEvent = latestEvent,
                transcripts = transcripts,
                bufferDepth = ring.size,
                droppedFrames = droppedFrames,
            )
        }
        onUpdate(snapshot)
    }

    /** Reset VAD, drain the buffer, and reset the ASR provider. Capture should be stopped first. */
    fun reset() {
        synchronized(lock) {
            ring.clear()
            vad.reset()
            vadState = VadState.SILENCE
            latestEvent = null
            droppedFrames = 0L
        }
        asrProvider.reset()
    }

    /** Drain a copy of the buffered frames in arrival order (oldest first). For inspection/tests. */
    fun drainBufferedFrames(): List<AudioFrame> = synchronized(lock) { ring.toList() }

    private fun enqueueDropOldest(frame: AudioFrame) {
        if (ring.size >= ringCapacity) {
            ring.pollFirst()
            droppedFrames += 1
        }
        ring.addLast(frame)
    }

    companion object {
        /** ~5s of 16 kHz audio at 320-sample (20 ms) frames — enough headroom, bounded memory. */
        const val DEFAULT_RING_CAPACITY: Int = 250
    }
}
