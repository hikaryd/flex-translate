package dev.flextranslate.foundation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class AudioCaptureController(private val context: Context) {
    data class CaptureStats(
        val isCapturing: Boolean,
        val sampleRateHz: Int,
        val framesRead: Long,
        val chunksRead: Long,
        val elapsedMs: Long,
        val peak: Int,
        val rms: Double,
        val lastError: String?,
    ) {
        /**
         * Заполнение индикатора уровня микрофона, 0..100. Считаем из [peak] по логарифмической шкале
         * dBFS, а не из сырого линейного отношения `rms/Short.MAX_VALUE`: для обычной речи линейный
         * RMS крошечный (~−25 dBFS), поэтому старый индикатор стоял почти на нуле. Мапим
         * [LEVEL_FLOOR_DBFS]..0 dBFS в 0..100, так что обычная речь заполняет заметную часть полосы и
         * при этом честно показывает запас. Сырые [rms]/[peak] не трогаем — они остаются для диагностики.
         */
        val levelPercent: Int = peakToLevelPercent(peak)
    }

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var running = false

    fun permissionState(): OfflineFirstState =
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            OfflineFirstState.ReadyOfflineAsr
        } else {
            OfflineFirstState.CaptureBlocked("Microphone permission is required for offline ASR")
        }

    fun createRecorder(sampleRateHz: Int = 16_000): AudioRecord? {
        if (permissionState() !is OfflineFirstState.ReadyOfflineAsr) return null
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
    }

    @Synchronized
    fun start(
        sampleRateHz: Int = 16_000,
        onStats: (CaptureStats) -> Unit,
        onFrame: (AudioFrame) -> Unit = {},
    ): Boolean {
        if (running) return true
        val audioRecord = createRecorder(sampleRateHz)
        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            onStats(CaptureStats(false, sampleRateHz, 0, 0, 0, 0, 0.0, "AudioRecord init failed"))
            audioRecord?.release()
            return false
        }

        recorder = audioRecord
        running = true
        captureThread = thread(name = "flex-mic-capture", isDaemon = true) {
            val buffer = ShortArray(maxOf(320, audioRecord.bufferSizeInFrames / 2))
            val startedAt = SystemClock.elapsedRealtime()
            var framesRead = 0L
            var chunksRead = 0L
            var lastEmitMs = 0L
            try {
                audioRecord.startRecording()
                while (running) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    val now = SystemClock.elapsedRealtime()
                    if (read > 0) {
                        framesRead += read
                        chunksRead += 1
                        // Копируем переиспользуемый буфер, чтобы кадр пережил следующий read().
                        onFrame(AudioFrame(pcm16 = buffer.copyOf(read), sampleRateHz = sampleRateHz, monotonicTsMs = now))
                        if (now - lastEmitMs >= 250L) {
                            lastEmitMs = now
                            onStats(statsFrom(buffer, read, sampleRateHz, framesRead, chunksRead, now - startedAt, null))
                        }
                    } else if (read < 0) {
                        onStats(CaptureStats(false, sampleRateHz, framesRead, chunksRead, now - startedAt, 0, 0.0, "AudioRecord read error $read"))
                        running = false
                    }
                }
            } catch (t: Throwable) {
                onStats(CaptureStats(false, sampleRateHz, framesRead, chunksRead, SystemClock.elapsedRealtime() - startedAt, 0, 0.0, t.message ?: t.javaClass.simpleName))
            } finally {
                running = false
                try {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
                } catch (_: Throwable) {
                }
                audioRecord.release()
                recorder = null
                onStats(CaptureStats(false, sampleRateHz, framesRead, chunksRead, SystemClock.elapsedRealtime() - startedAt, 0, 0.0, null))
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        captureThread?.join(750)
        captureThread = null
    }

    fun isCapturing(): Boolean = running

    private fun statsFrom(
        buffer: ShortArray,
        count: Int,
        sampleRateHz: Int,
        framesRead: Long,
        chunksRead: Long,
        elapsedMs: Long,
        lastError: String?,
    ): CaptureStats {
        var sumSquares = 0.0
        var peak = 0
        for (i in 0 until count) {
            val value = buffer[i].toInt()
            val absValue = abs(value)
            if (absValue > peak) peak = absValue
            sumSquares += value.toDouble() * value.toDouble()
        }
        return CaptureStats(
            isCapturing = true,
            sampleRateHz = sampleRateHz,
            framesRead = framesRead,
            chunksRead = chunksRead,
            elapsedMs = elapsedMs,
            peak = peak,
            rms = sqrt(sumSquares / count.coerceAtLeast(1)),
            lastError = lastError,
        )
    }

    companion object {
        /** Самый тихий уровень, который ещё рисуем ненулевой полосой. −60 dBFS ≈ совсем тихая комната; ниже — 0%. */
        const val LEVEL_FLOOR_DBFS: Double = -60.0

        /**
         * Опорная величина для 0 dBFS. Цикл захвата считает `abs(sample)`, поэтому сэмпл максимальной
         * амплитуды читается как [Short.MAX_VALUE] (а редкий −32768 — как 32768); оба дают ≥ 0 dBFS и
         * зажимаются до 100%.
         */
        private const val FULL_SCALE: Double = 32_767.0

        /**
         * Переводит 16-битную амплитуду [peak] (0..32768) в заполнение индикатора 0..100 по шкале
         * dBFS. Тишина (peak 0) → 0; полная шкала (0 dBFS) → 100; [LEVEL_FLOOR_DBFS] → 0. Обычная
         * речь (пики около −20..−6 dBFS) попадает в ~65..90%, так что полоса заметно двигается.
         */
        fun peakToLevelPercent(peak: Int): Int {
            if (peak <= 0) return 0
            val dbfs = 20.0 * log10(peak / FULL_SCALE)
            val fraction = (dbfs - LEVEL_FLOOR_DBFS) / (0.0 - LEVEL_FLOOR_DBFS)
            return Math.round(fraction * 100.0).toInt().coerceIn(0, 100)
        }
    }
}
