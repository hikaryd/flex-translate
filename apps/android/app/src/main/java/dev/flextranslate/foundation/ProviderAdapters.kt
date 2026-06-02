package dev.flextranslate.foundation

data class AudioFrame(val pcm16: ShortArray, val sampleRateHz: Int, val monotonicTsMs: Long)
data class TranscriptEvent(val text: String, val isFinal: Boolean, val monotonicTsMs: Long)
data class TranslationResult(val text: String?, val unsupportedReason: String?)
data class CloudConsent(val enabled: Boolean, val credentialSource: String?)

interface AsrProvider {
    val providerId: String
    fun accept(frame: AudioFrame): List<TranscriptEvent>
    fun reset()
}

interface TranslationProvider {
    val providerId: String
    fun translate(text: String, languagePair: String, deviceTier: String): TranslationResult

    /** Освобождает тяжёлые нативные ресурсы/сессии. Для stateless-провайдеров — no-op. */
    fun close() = Unit
}

interface CloudProvider {
    val providerId: String
    fun isAvailable(consent: CloudConsent): Boolean
}

class PlaceholderLocalAsrProvider : AsrProvider {
    override val providerId = "placeholder-local-asr"
    override fun accept(frame: AudioFrame): List<TranscriptEvent> = emptyList()
    override fun reset() = Unit
}
