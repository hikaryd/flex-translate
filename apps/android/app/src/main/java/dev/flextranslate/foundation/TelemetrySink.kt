package dev.flextranslate.foundation

import android.os.SystemClock
import java.io.File

/**
 * On-device, in-memory bounded ring buffer of [TelemetryEvent]s.
 *
 * Privacy contract:
 * - NO network I/O, ever. Telemetry is strictly local to the device.
 * - The only off-memory output is an optional debug JSONL file in the app's filesDir
 *   (debug builds only, never shipped to a server).
 * - Fields in each event are limited to what the schema defines: no audio, no transcripts,
 *   no free-form PII beyond the schema's typed fields.
 *
 * Thread safety: all public methods are guarded by [lock].
 */
class TelemetrySink(
    /** Maximum events retained in memory. Oldest is dropped on overflow. */
    private val capacity: Int = DEFAULT_CAPACITY,
    /**
     * When non-null (debug builds only), every accepted event is appended as a JSONL line
     * to this file. Production callers pass null.
     */
    private val debugJsonlFile: File? = null,
    /** Clock for [TelemetryEvent.monotonicTsMs]. Overridable in tests. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val lock = Any()

    /** Ring buffer backed by an ArrayDeque; add to tail, drop from head on overflow. */
    private val ring = ArrayDeque<TelemetryEvent>(capacity)

    /** Total events ever accepted (monotonically increasing, never reset). */
    var totalAccepted: Long = 0L
        private set

    /** Total events dropped due to the ring being full. */
    var totalDropped: Long = 0L
        private set

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /**
     * Accept [event] into the ring, stamping [TelemetryEvent.monotonicTsMs] from [clock] if the
     * event carries a zero timestamp (callers may supply their own ts for better accuracy).
     */
    fun accept(event: TelemetryEvent) {
        val stamped = if (event.monotonicTsMs == 0L) {
            event.copy(monotonicTsMs = clock())
        } else {
            event
        }
        synchronized(lock) {
            if (ring.size >= capacity) {
                ring.removeFirst()
                totalDropped += 1
            }
            ring.addLast(stamped)
            totalAccepted += 1
        }
        debugJsonlFile?.let { appendJsonl(it, stamped) }
    }

    /**
     * Build and accept an event in one call. [monotonicTsMs] is auto-filled from [clock] when 0.
     */
    fun emit(
        sessionId: String,
        eventType: String,
        deviceTier: String,
        deviceModel: String,
        osVersion: String,
        runtimeId: String,
        modelId: String,
        languagePair: String,
        mode: String,
        networkState: String,
        appBuild: String,
        monotonicTsMs: Long = 0L,
        payload: Map<String, String> = emptyMap(),
    ) {
        accept(
            TelemetryEvent(
                sessionId = sessionId,
                monotonicTsMs = if (monotonicTsMs != 0L) monotonicTsMs else clock(),
                eventType = eventType,
                deviceTier = deviceTier,
                deviceModel = deviceModel,
                osVersion = osVersion,
                runtimeId = runtimeId,
                modelId = modelId,
                languagePair = languagePair,
                mode = mode,
                networkState = networkState,
                appBuild = appBuild,
                payload = payload,
            )
        )
    }

    /** Return a snapshot of the last [n] events, newest last (arrival order). */
    fun recent(n: Int = capacity): List<TelemetryEvent> = synchronized(lock) {
        if (ring.isEmpty()) return@synchronized emptyList()
        val take = n.coerceIn(1, ring.size)
        ring.takeLast(take)
    }

    /** Derive p50 / p95 of a numeric payload field (e.g. `"latency_ms"`) from recent events. */
    fun latencyPercentiles(eventType: String, payloadKey: String): Percentiles {
        val samples = synchronized(lock) {
            ring.filter { it.eventType == eventType }
                .mapNotNull { it.payload[payloadKey]?.toLongOrNull() }
        }
        return computePercentiles(samples)
    }

    /** Clear the ring buffer. Used in tests; production callers should generally not call this. */
    fun clear() = synchronized(lock) {
        ring.clear()
    }

    /** Current count of events in the buffer. */
    val size: Int get() = synchronized(lock) { ring.size }

    // ---- debug JSONL export (no-op when debugJsonlFile is null) ---------------------------------

    private fun appendJsonl(file: File, event: TelemetryEvent) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(eventToJsonLine(event) + "\n")
        }
        // Silently ignore I/O errors — telemetry must never crash the host.
    }

    internal fun eventToJsonLine(event: TelemetryEvent): String {
        val payloadPart = if (event.payload.isEmpty()) {
            ""
        } else {
            val entries = event.payload.entries.joinToString(",") { (k, v) ->
                "\"${k.escapeJson()}\":\"${v.escapeJson()}\""
            }
            ",\"payload\":{$entries}"
        }
        return "{" +
            "\"session_id\":\"${event.sessionId.escapeJson()}\"," +
            "\"monotonic_ts_ms\":${event.monotonicTsMs}," +
            "\"event_type\":\"${event.eventType.escapeJson()}\"," +
            "\"device_tier\":\"${event.deviceTier.escapeJson()}\"," +
            "\"device_model\":\"${event.deviceModel.escapeJson()}\"," +
            "\"os_version\":\"${event.osVersion.escapeJson()}\"," +
            "\"runtime_id\":\"${event.runtimeId.escapeJson()}\"," +
            "\"model_id\":\"${event.modelId.escapeJson()}\"," +
            "\"language_pair\":\"${event.languagePair.escapeJson()}\"," +
            "\"mode\":\"${event.mode.escapeJson()}\"," +
            "\"network_state\":\"${event.networkState.escapeJson()}\"," +
            "\"app_build\":\"${event.appBuild.escapeJson()}\"" +
            payloadPart +
            "}"
    }

    private fun String.escapeJson(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    companion object {
        /** Keep the last 500 events in memory — ~500 * ~300 bytes ≈ 150 KB maximum. */
        const val DEFAULT_CAPACITY = 500

        /** Schema-defined event type constants — avoids stringly-typed scatter in callers. */
        const val EVT_SESSION_START = "audio_callback_received"  // closest schema type for session start context
        const val EVT_VAD_SPEECH_START = "vad_speech_start"
        const val EVT_VAD_SPEECH_END = "vad_speech_end"
        const val EVT_ASR_PARTIAL = "asr_partial_emitted"
        const val EVT_ASR_FINAL = "asr_final_emitted"
        const val EVT_MT_START = "mt_request_started"
        const val EVT_MT_END = "mt_result_emitted"
        const val EVT_NETWORK_CALL = "network_request_attempted"

        /** Mode values (schema enum). */
        const val MODE_OFFLINE = "offline"
        const val MODE_CLOUD = "gemini_batch"

        /** Network state values (schema enum). */
        const val NET_ONLINE = "online"
        const val NET_OFFLINE = "offline"
        const val NET_UNKNOWN = "unknown"

        /** Device tier values (schema enum). */
        const val TIER_HIGH = "high"
        const val TIER_MID = "mid"
        const val TIER_LOW = "low"
        const val TIER_UNKNOWN = "unknown"
    }
}

/** p50 and p95 latency in ms, or null when there are fewer samples than needed. */
data class Percentiles(
    val p50Ms: Long?,
    val p95Ms: Long?,
    val sampleCount: Int,
)

internal fun computePercentiles(samples: List<Long>): Percentiles {
    if (samples.isEmpty()) return Percentiles(null, null, 0)
    val sorted = samples.sorted()
    val n = sorted.size
    val p50 = sorted[(n * 50 / 100).coerceIn(0, n - 1)]
    val p95 = sorted[(n * 95 / 100).coerceIn(0, n - 1)]
    return Percentiles(p50Ms = p50, p95Ms = p95, sampleCount = n)
}
