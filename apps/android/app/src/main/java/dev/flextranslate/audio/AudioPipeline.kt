package dev.flextranslate.audio

import dev.flextranslate.foundation.AsrProvider
import dev.flextranslate.foundation.AudioFrame
import dev.flextranslate.foundation.TranscriptEvent
import java.util.ArrayDeque

/**
 * Связка между сырыми кадрами с микрофона и стеком распознавания.
 *
 * На каждый принятый [AudioFrame] конвейер:
 *  1. отдаёт кадр в подставленный [AsrProvider] и собирает [TranscriptEvent]'ы,
 *  2. кладёт кадр в кольцевой буфер ограниченного размера с backpressure «выкидываем самый старый»
 *     (медленный/залипший потребитель не раздувает память — вместо этого теряется старейший кадр),
 *  3. прогоняет подставленный [Vad] и запоминает последние [VadState] и [VadEvent],
 *  4. публикует [TranscriptEvent]'ы и снимок состояния в необязательный наблюдатель [onUpdate].
 *
 * Потоки: [accept] вызывается ОДНИМ потоком захвата (распознаватель держит своё состояние стрима и
 * не реентерабелен), поэтому вызов [AsrProvider.accept] из шага 1 идёт ВНЕ [lock] — лок защищает
 * только общие поля VAD/буфера/снимка, чтобы параллельный читатель (например, [currentVadState] из
 * UI-потока) видел согласованный снимок. Наблюдатель тоже зовём вне лока — иначе словим реентрантный
 * дедлок.
 */
class AudioPipeline(
    private val asrProvider: AsrProvider,
    private val vad: Vad,
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
    private val onUpdate: (Snapshot) -> Unit = {},
) {

    /** Неизменяемый снимок конвейера после обработки кадра. */
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

    /** Обрабатывает один захваченный кадр. Зовётся из потока захвата. */
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

    /** Сбрасывает VAD, чистит буфер и ресетит ASR-провайдер. Захват сначала надо остановить. */
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

    /** Копия буферизованных кадров в порядке поступления (сначала старые). Для тестов/инспекции. */
    fun drainBufferedFrames(): List<AudioFrame> = synchronized(lock) { ring.toList() }

    private fun enqueueDropOldest(frame: AudioFrame) {
        if (ring.size >= ringCapacity) {
            ring.pollFirst()
            droppedFrames += 1
        }
        ring.addLast(frame)
    }

    companion object {
        /** ~5 с аудио 16 кГц при кадрах по 320 сэмплов (20 мс) — запас есть, память ограничена. */
        const val DEFAULT_RING_CAPACITY: Int = 250
    }
}
