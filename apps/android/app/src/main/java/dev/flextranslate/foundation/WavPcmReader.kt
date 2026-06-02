package dev.flextranslate.foundation

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Минимальный читатель RIFF/WAVE для 16-битного PCM — ровно столько, чтобы прогнать известный
 * тестовый клип через настоящий распознаватель в self-test демо A2 на устройстве. Это не
 * универсальный WAV-декодер: поддерживает только mono/stereo 16-bit PCM с раскладкой
 * `fmt `+`data`, как в сэмплах sherpa-onnx.
 */
object WavPcmReader {

    data class Clip(val pcm16: ShortArray, val sampleRateHz: Int)

    /** Читает [file] как 16-битный PCM. Возвращает null, если файла нет или это не 16-bit PCM WAV. */
    fun read(file: File): Clip? {
        if (!file.isFile || file.length() < HEADER_MIN_BYTES) return null
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (tag(buffer, 0) != "RIFF" || tag(buffer, 8) != "WAVE") return null

        var offset = 12
        var sampleRate = 0
        var channels = 1
        var bitsPerSample = 16
        while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
            val chunkId = tag(buffer, offset)
            val chunkSize = buffer.getInt(offset + 4)
            val body = offset + CHUNK_HEADER_BYTES
            when (chunkId) {
                "fmt " -> {
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bitsPerSample = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (bitsPerSample != BITS_16 || sampleRate <= 0) return null
                    val dataLen = chunkSize.coerceAtMost(bytes.size - body)
                    return Clip(toMonoShorts(buffer, body, dataLen, channels), sampleRate)
                }
            }
            if (chunkSize <= 0) break
            offset = body + chunkSize + (chunkSize and 1) // чанки выровнены по слову
        }
        return null
    }

    private fun toMonoShorts(buffer: ByteBuffer, start: Int, lengthBytes: Int, channels: Int): ShortArray {
        val totalSamples = lengthBytes / BYTES_PER_SAMPLE
        if (channels <= 1) {
            return ShortArray(totalSamples) { index -> buffer.getShort(start + index * BYTES_PER_SAMPLE) }
        }
        // Сводим чередующиеся каналы усреднением — сохраняем масштаб амплитуды, без клиппинга.
        val frames = totalSamples / channels
        return ShortArray(frames) { frame ->
            var sum = 0
            for (channel in 0 until channels) {
                sum += buffer.getShort(start + (frame * channels + channel) * BYTES_PER_SAMPLE)
            }
            (sum / channels).toShort()
        }
    }

    private fun tag(buffer: ByteBuffer, offset: Int): String =
        String(ByteArray(4) { buffer.get(offset + it) }, Charsets.US_ASCII)

    private const val HEADER_MIN_BYTES = 44
    private const val CHUNK_HEADER_BYTES = 8
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_16 = 16
}
