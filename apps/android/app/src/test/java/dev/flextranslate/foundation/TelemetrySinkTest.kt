package dev.flextranslate.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic JVM tests for [TelemetrySink]: ring bound, ordering, thread-safety basics,
 * p50/p95 computation from a known sample set, monotonic-timestamp auto-stamp, and JSONL
 * field completeness vs the schema's required fields.
 *
 * No Android framework dependencies — clock is injected, no SystemClock usage.
 */
class TelemetrySinkTest {

    // ---- helpers --------------------------------------------------------------------------------

    private val ts = AtomicLong(1_000L)

    /** A sink with a deterministic monotonically incrementing clock (1 ms per call). */
    private fun testSink(capacity: Int = 100): TelemetrySink =
        TelemetrySink(capacity = capacity, clock = { ts.getAndIncrement() })

    private fun makeEvent(
        eventType: String = TelemetrySink.EVT_ASR_FINAL,
        tsMs: Long = 0L,
        payload: Map<String, String> = emptyMap(),
    ): TelemetryEvent = TelemetryEvent(
        sessionId = "test-session",
        monotonicTsMs = tsMs,
        eventType = eventType,
        deviceTier = TelemetrySink.TIER_HIGH,
        deviceModel = "TestDevice",
        osVersion = "Android 14",
        runtimeId = "sherpa-onnx:ru-t-one",
        modelId = "m2m100-418m-onnx",
        languagePair = "ru->en",
        mode = TelemetrySink.MODE_OFFLINE,
        networkState = TelemetrySink.NET_UNKNOWN,
        appBuild = "0.1.0",
        payload = payload,
    )

    // ---- ring bound tests -----------------------------------------------------------------------

    @Test
    fun `ring buffer never exceeds capacity`() {
        val sink = testSink(capacity = 5)
        repeat(10) { sink.accept(makeEvent()) }

        assertEquals("size must equal capacity", 5, sink.size)
        assertEquals("totalAccepted tracks all", 10L, sink.totalAccepted)
        assertEquals("totalDropped tracks overflow", 5L, sink.totalDropped)
    }

    @Test
    fun `recent returns up to n events newest-last`() {
        val sink = testSink(capacity = 20)
        repeat(10) { i -> sink.accept(makeEvent(tsMs = i.toLong() + 1)) }

        val recent5 = sink.recent(5)
        assertEquals(5, recent5.size)
        // Newest-last: the last 5 accepted have ts 6..10
        assertEquals(6L, recent5.first().monotonicTsMs)
        assertEquals(10L, recent5.last().monotonicTsMs)
    }

    @Test
    fun `recent with n larger than size returns all events`() {
        val sink = testSink(capacity = 20)
        repeat(3) { sink.accept(makeEvent()) }

        assertEquals(3, sink.recent(100).size)
    }

    @Test
    fun `recent on empty sink returns empty list`() {
        val sink = testSink()
        assertTrue(sink.recent(10).isEmpty())
    }

    @Test
    fun `oldest events are dropped when ring is full`() {
        val sink = testSink(capacity = 3)
        (1L..5L).forEach { ts -> sink.accept(makeEvent(tsMs = ts)) }

        val retained = sink.recent(10).map { it.monotonicTsMs }
        assertEquals("Three most recent retained", listOf(3L, 4L, 5L), retained)
    }

    // ---- ordering test --------------------------------------------------------------------------

    @Test
    fun `events are stored and returned in arrival order`() {
        val sink = testSink(capacity = 10)
        val tsList = listOf(100L, 200L, 300L, 400L, 500L)
        tsList.forEach { ts -> sink.accept(makeEvent(tsMs = ts)) }

        val returned = sink.recent(10).map { it.monotonicTsMs }
        assertEquals(tsList, returned)
    }

    // ---- auto-timestamp test --------------------------------------------------------------------

    @Test
    fun `monotonic timestamp is auto-filled when event carries zero ts`() {
        val sink = testSink()
        sink.accept(makeEvent(tsMs = 0L))

        val event = sink.recent(1).single()
        assertTrue("auto-stamped ts must be positive", event.monotonicTsMs > 0L)
    }

    @Test
    fun `explicit non-zero timestamp is preserved unchanged`() {
        val sink = testSink()
        sink.accept(makeEvent(tsMs = 42_000L))

        assertEquals(42_000L, sink.recent(1).single().monotonicTsMs)
    }

    // ---- p50/p95 computation tests --------------------------------------------------------------

    @Test
    fun `p50 and p95 are computed correctly from a known sample set`() {
        // 10 samples: 10, 20, 30, ..., 100 ms
        val samples = (1..10).map { it * 10L }
        val percentiles = computePercentiles(samples)

        // p50 index = 10*50/100 = 5 → sorted[5] = 60
        assertEquals(60L, percentiles.p50Ms)
        // p95 index = 10*95/100 = 9 → sorted[9] = 100
        assertEquals(100L, percentiles.p95Ms)
        assertEquals(10, percentiles.sampleCount)
    }

    @Test
    fun `percentiles return null when no samples exist`() {
        val percentiles = computePercentiles(emptyList())

        assertNull(percentiles.p50Ms)
        assertNull(percentiles.p95Ms)
        assertEquals(0, percentiles.sampleCount)
    }

    @Test
    fun `single sample gives p50 and p95 equal to that sample`() {
        val percentiles = computePercentiles(listOf(75L))

        assertEquals(75L, percentiles.p50Ms)
        assertEquals(75L, percentiles.p95Ms)
        assertEquals(1, percentiles.sampleCount)
    }

    @Test
    fun `latencyPercentiles extracts from matching events only`() {
        val sink = testSink(capacity = 50)
        // 5 mt_result_emitted events with latency_ms payload
        listOf(10L, 20L, 30L, 40L, 50L).forEach { lat ->
            sink.accept(makeEvent(
                eventType = TelemetrySink.EVT_MT_END,
                tsMs = lat,
                payload = mapOf("latency_ms" to lat.toString()),
            ))
        }
        // Add some other event types that must NOT contribute to MT latency
        repeat(3) {
            sink.accept(makeEvent(eventType = TelemetrySink.EVT_ASR_FINAL))
        }

        val p = sink.latencyPercentiles(TelemetrySink.EVT_MT_END, "latency_ms")
        assertEquals(5, p.sampleCount)
        assertNotNull(p.p50Ms)
        assertNotNull(p.p95Ms)
    }

    @Test
    fun `latencyPercentiles returns nulls when no matching events exist`() {
        val sink = testSink()
        val p = sink.latencyPercentiles(TelemetrySink.EVT_MT_END, "latency_ms")

        assertNull(p.p50Ms)
        assertNull(p.p95Ms)
        assertEquals(0, p.sampleCount)
    }

    // ---- JSONL field completeness vs schema required fields -------------------------------------

    /**
     * The schema requires: session_id, monotonic_ts_ms, event_type, device_tier, device_model,
     * os_version, runtime_id, model_id, language_pair, mode, network_state, app_build.
     * Verify that [TelemetrySink.eventToJsonLine] emits every required field.
     */
    @Test
    fun `JSONL line contains all schema-required fields`() {
        val sink = testSink()
        val event = makeEvent(tsMs = 9_999L)
        val line = sink.eventToJsonLine(event)

        val requiredKeys = listOf(
            "session_id",
            "monotonic_ts_ms",
            "event_type",
            "device_tier",
            "device_model",
            "os_version",
            "runtime_id",
            "model_id",
            "language_pair",
            "mode",
            "network_state",
            "app_build",
        )
        requiredKeys.forEach { key ->
            assertTrue("JSONL line must contain key '$key'", line.contains("\"$key\""))
        }
    }

    @Test
    fun `JSONL line is valid JSON-like structure (starts and ends with braces)`() {
        val sink = testSink()
        val line = sink.eventToJsonLine(makeEvent())

        assertTrue("must start with {", line.startsWith("{"))
        assertTrue("must end with }", line.endsWith("}"))
    }

    @Test
    fun `JSONL payload field is emitted when non-empty`() {
        val sink = testSink()
        val event = makeEvent(payload = mapOf("latency_ms" to "42", "provider" to "m2m100"))
        val line = sink.eventToJsonLine(event)

        assertTrue(line.contains("\"payload\""))
        assertTrue(line.contains("\"latency_ms\""))
        assertTrue(line.contains("\"42\""))
    }

    @Test
    fun `JSONL payload field is absent when empty`() {
        val sink = testSink()
        val line = sink.eventToJsonLine(makeEvent(payload = emptyMap()))

        assertTrue("no payload key when empty", !line.contains("\"payload\""))
    }

    @Test
    fun `JSONL escapes double-quotes in string values`() {
        val sink = testSink()
        val event = makeEvent().copy(deviceModel = "Device \"X\"")
        val line = sink.eventToJsonLine(event)

        assertTrue("quotes must be escaped", line.contains("Device \\\"X\\\""))
    }

    // ---- thread-safety basics -------------------------------------------------------------------

    @Test
    fun `concurrent accept from multiple threads does not lose counts or corrupt state`() {
        val sink = TelemetrySink(capacity = 1000, clock = { System.nanoTime() / 1_000_000 })
        val threadCount = 8
        val eventsPerThread = 50
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                repeat(eventsPerThread) { sink.accept(makeEvent()) }
                latch.countDown()
            }
        }

        val finished = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("all threads finished", finished)
        assertEquals(
            "all events counted",
            (threadCount * eventsPerThread).toLong(),
            sink.totalAccepted,
        )
        assertTrue("size <= capacity", sink.size <= 1000)
        assertEquals(
            "dropped = accepted - size",
            sink.totalAccepted - sink.size,
            sink.totalDropped,
        )
    }

    // ---- emit convenience API -------------------------------------------------------------------

    @Test
    fun `emit builds and accepts a fully populated event`() {
        val sink = testSink()
        sink.emit(
            sessionId = "s1",
            eventType = TelemetrySink.EVT_VAD_SPEECH_START,
            deviceTier = TelemetrySink.TIER_MID,
            deviceModel = "Pixel 7",
            osVersion = "Android 14",
            runtimeId = "sherpa-onnx:ru-t-one",
            modelId = "none",
            languagePair = "ru->en",
            mode = TelemetrySink.MODE_OFFLINE,
            networkState = TelemetrySink.NET_OFFLINE,
            appBuild = "0.1.0",
            monotonicTsMs = 5_000L,
        )

        val event = sink.recent(1).single()
        assertEquals("s1", event.sessionId)
        assertEquals(TelemetrySink.EVT_VAD_SPEECH_START, event.eventType)
        assertEquals(5_000L, event.monotonicTsMs)
        assertEquals(TelemetrySink.TIER_MID, event.deviceTier)
    }
}
