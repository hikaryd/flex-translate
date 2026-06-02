package dev.flextranslate.foundation

import android.os.SystemClock
import java.io.File

/**
 * Кольцевой буфер [TelemetryEvent] фиксированного размера — всё в памяти, на устройстве.
 *
 * Контракт приватности:
 * - Никакого сетевого I/O. Телеметрия не покидает устройство.
 * - Единственный выход за пределы памяти — опциональный отладочный JSONL-файл в filesDir
 *   (только debug-сборки, на сервер никогда не уходит).
 * - Поля события ограничены схемой: ни аудио, ни транскриптов, ни произвольных персональных
 *   данных сверх типизированных полей схемы.
 *
 * Потокобезопасность: все публичные методы под [lock].
 */
class TelemetrySink(
    /** Сколько событий держим в памяти. При переполнении вылетает самое старое. */
    private val capacity: Int = DEFAULT_CAPACITY,
    /**
     * Если не null (только debug-сборки), каждое принятое событие дописывается JSONL-строкой
     * в этот файл. В проде сюда передают null.
     */
    private val debugJsonlFile: File? = null,
    /** Часы для [TelemetryEvent.monotonicTsMs]. В тестах можно подменить. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val lock = Any()

    /** Кольцо на базе ArrayDeque: добавляем в хвост, при переполнении срезаем голову. */
    private val ring = ArrayDeque<TelemetryEvent>(capacity)

    /** Сколько событий принято за всё время (только растёт, не сбрасывается). */
    var totalAccepted: Long = 0L
        private set

    /** Сколько событий выкинули из-за переполнения кольца. */
    var totalDropped: Long = 0L
        private set

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /**
     * Кладёт [event] в кольцо. Если у события нулевой таймстамп — проставляет [TelemetryEvent.monotonicTsMs]
     * из [clock] (вызывающий может передать свой ts для большей точности).
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
     * Собрать и принять событие одним вызовом. [monotonicTsMs] при нуле подставляется из [clock].
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

    /** Снимок последних [n] событий в порядке поступления (свежие — в конце). */
    fun recent(n: Int = capacity): List<TelemetryEvent> = synchronized(lock) {
        if (ring.isEmpty()) return@synchronized emptyList()
        val take = n.coerceIn(1, ring.size)
        ring.takeLast(take)
    }

    /** Считает p50 / p95 по числовому полю payload (например `"latency_ms"`) из недавних событий. */
    fun latencyPercentiles(eventType: String, payloadKey: String): Percentiles {
        val samples = synchronized(lock) {
            ring.filter { it.eventType == eventType }
                .mapNotNull { it.payload[payloadKey]?.toLongOrNull() }
        }
        return computePercentiles(samples)
    }

    /** Очистить кольцо. Для тестов; в проде вызывать обычно незачем. */
    fun clear() = synchronized(lock) {
        ring.clear()
    }

    /** Сколько событий сейчас в буфере. */
    val size: Int get() = synchronized(lock) { ring.size }

    // ---- отладочный JSONL-экспорт (ничего не делает, когда debugJsonlFile == null) --------------

    private fun appendJsonl(file: File, event: TelemetryEvent) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(eventToJsonLine(event) + "\n")
        }
        // Ошибки I/O молча глотаем — телеметрия не имеет права уронить приложение.
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
        /** Держим последние 500 событий — это максимум ~500 * ~300 байт ≈ 150 КБ. */
        const val DEFAULT_CAPACITY = 500

        /** Типы событий из схемы — чтобы не размазывать сырые строки по вызывающему коду. */
        const val EVT_SESSION_START = "audio_callback_received"  // ближайший к старту сессии тип из схемы
        const val EVT_VAD_SPEECH_START = "vad_speech_start"
        const val EVT_VAD_SPEECH_END = "vad_speech_end"
        const val EVT_ASR_PARTIAL = "asr_partial_emitted"
        const val EVT_ASR_FINAL = "asr_final_emitted"
        const val EVT_MT_START = "mt_request_started"
        const val EVT_MT_END = "mt_result_emitted"
        const val EVT_NETWORK_CALL = "network_request_attempted"

        /** Значения mode (enum из схемы). */
        const val MODE_OFFLINE = "offline"
        const val MODE_CLOUD = "gemini_batch"

        /** Значения networkState (enum из схемы). */
        const val NET_ONLINE = "online"
        const val NET_OFFLINE = "offline"
        const val NET_UNKNOWN = "unknown"

        /** Значения deviceTier (enum из схемы). */
        const val TIER_HIGH = "high"
        const val TIER_MID = "mid"
        const val TIER_LOW = "low"
        const val TIER_UNKNOWN = "unknown"
    }
}

/** Задержки p50 и p95 в мс; null, когда выборки не хватает. */
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
