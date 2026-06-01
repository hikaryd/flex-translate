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
        val levelPercent: Int = ((rms / Short.MAX_VALUE) * 100.0).toInt().coerceIn(0, 100)
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
                        // Copy the reused buffer so the frame can outlive the next read().
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
}
