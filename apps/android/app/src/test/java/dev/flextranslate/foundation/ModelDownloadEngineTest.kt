package dev.flextranslate.foundation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic JVM tests for [ModelDownloadEngine]. A minimal raw-socket HTTP server (no JDK
 * `com.sun.*` API, which is absent from the Android unit-test bootclasspath) serves known bytes with
 * Range support, so resume / checksum-verify / rollback / idempotency are exercised against a
 * genuine HTTP round-trip — no Android, no real network, no mocking of the engine itself.
 */
class ModelDownloadEngineTest {

    private lateinit var server: FakeHttpServer
    private lateinit var tmpDir: File

    // The "model file" the server hosts: 200 KB of deterministic bytes + its real SHA-256.
    private val payload: ByteArray = ByteArray(200_000) { (it % 251).toByte() }
    private val payloadSha: String = sha256(payload)

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("model-dl-test").toFile()
        server = FakeHttpServer(payload).also { it.start() }
    }

    @After
    fun tearDown() {
        server.stop()
        tmpDir.deleteRecursively()
    }

    private fun specFor(corrupt: Boolean = false) = ModelDownloadSpec(
        modelId = "test-pack",
        files = listOf(
            ModelFileDownload(
                fileName = "model.bin",
                sourceUrl = "http://127.0.0.1:${server.port}/${if (corrupt) "corrupt.bin" else "model.bin"}",
                sha256 = payloadSha,
                sizeBytes = payload.size.toLong(),
            ),
        ),
    )

    private fun modelDir() = File(tmpDir, "test-pack")

    @Test
    fun `full download verifies checksum and atomically installs the file`() {
        val engine = ModelDownloadEngine()
        var lastProgress = 0L
        val result = engine.download(specFor(), modelDir(), AtomicBoolean(false)) {
            lastProgress = it.bytesDone
        }
        assertEquals(ModelDownloadEngine.Result.Success, result)
        val installed = File(modelDir(), "model.bin")
        assertTrue("Final file must exist", installed.isFile)
        assertEquals(payload.size.toLong(), installed.length())
        assertEquals("Bytes must match the server payload", payloadSha, sha256(installed.readBytes()))
        assertEquals("Progress must reach the full size", payload.size.toLong(), lastProgress)
        assertFalse(".part must be gone after install", File(modelDir(), "model.bin.part").exists())
    }

    @Test
    fun `resume continues from an existing part via Range and only fetches the tail`() {
        modelDir().mkdirs()
        // Pre-seed a .part with the first 120 KB of the real payload (a clean partial download).
        val prefixLen = 120_000
        File(modelDir(), "model.bin.part").writeBytes(payload.copyOfRange(0, prefixLen))

        val engine = ModelDownloadEngine()
        val result = engine.download(specFor(), modelDir(), AtomicBoolean(false)) {}

        assertEquals(ModelDownloadEngine.Result.Success, result)
        assertTrue("Server must have served a partial (206) response", server.lastWasPartial.get())
        assertEquals(
            "Only the missing tail should be fetched",
            payload.size - prefixLen,
            server.lastSentBytes.get(),
        )
        assertEquals(payloadSha, sha256(File(modelDir(), "model.bin").readBytes()))
    }

    @Test
    fun `checksum mismatch rolls back the part and installs nothing`() {
        val engine = ModelDownloadEngine()
        // Source serves the right SIZE but zeroed bytes; the spec's sha is the real payload's.
        val result = engine.download(specFor(corrupt = true), modelDir(), AtomicBoolean(false)) {}

        assertTrue("Must fail on checksum", result is ModelDownloadEngine.Result.Failure)
        assertFalse("No final file on corrupt download", File(modelDir(), "model.bin").exists())
        assertFalse(".part must be rolled back on mismatch", File(modelDir(), "model.bin.part").exists())
    }

    @Test
    fun `oversized stale part is discarded before resuming`() {
        modelDir().mkdirs()
        // A corrupt/oversized .part (bigger than the real file) must be dropped, not resumed from.
        File(modelDir(), "model.bin.part").writeBytes(ByteArray(payload.size + 50_000) { 1 })

        val engine = ModelDownloadEngine()
        val result = engine.download(specFor(), modelDir(), AtomicBoolean(false)) {}

        assertEquals(ModelDownloadEngine.Result.Success, result)
        assertFalse("Server must NOT have been asked for a partial", server.lastWasPartial.get())
        assertEquals(payloadSha, sha256(File(modelDir(), "model.bin").readBytes()))
    }

    @Test
    fun `already installed and verified file is skipped (idempotent)`() {
        modelDir().mkdirs()
        File(modelDir(), "model.bin").writeBytes(payload)
        server.lastSentBytes.set(-1) // sentinel: if the server is hit, this changes.

        val engine = ModelDownloadEngine()
        var finalProgress = 0L
        val result = engine.download(specFor(), modelDir(), AtomicBoolean(false)) {
            finalProgress = it.bytesDone
        }

        assertEquals(ModelDownloadEngine.Result.Success, result)
        assertEquals("Verified file must not be re-fetched", -1, server.lastSentBytes.get())
        assertEquals("Pre-installed bytes count toward progress", payload.size.toLong(), finalProgress)
    }

    @Test
    fun `cancellation before start returns Cancelled and writes no final file`() {
        val engine = ModelDownloadEngine()
        val result = engine.download(specFor(), modelDir(), AtomicBoolean(true)) {}
        assertEquals(ModelDownloadEngine.Result.Cancelled, result)
        assertFalse(File(modelDir(), "model.bin").exists())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Tiny single-threaded raw-socket HTTP/1.0 server for the engine tests. Serves [payload] at
 * `/model.bin` (and a same-size but zeroed body at `/corrupt.bin`), honouring a `Range: bytes=N-`
 * header with a 206 + tail. Built on `java.net.ServerSocket` only so it works on the Android
 * unit-test JVM (which lacks `com.sun.net.httpserver`).
 */
private class FakeHttpServer(private val payload: ByteArray) {
    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort
    val lastSentBytes = AtomicInteger(0)
    val lastWasPartial = AtomicBoolean(false)
    private val worker = Thread(::loop, "fake-http").apply { isDaemon = true }

    fun start() = worker.start()

    fun stop() {
        runCatching { socket.close() }
    }

    private fun loop() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            runCatching { handle(client) }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val path = requestLine.split(" ").getOrElse(1) { "/" }
        var rangeStart = 0
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Range:", ignoreCase = true)) {
                rangeStart = header.substringAfter("bytes=").substringBefore("-").trim().toIntOrNull() ?: 0
            }
        }
        val body = if (path.contains("corrupt")) ByteArray(payload.size) else payload
        val start = rangeStart.coerceIn(0, body.size)
        val slice = body.copyOfRange(start, body.size)
        lastSentBytes.set(slice.size)
        lastWasPartial.set(start > 0)
        val status = if (start > 0) "206 Partial Content" else "200 OK"
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.0 $status\r\nContent-Length: ${slice.size}\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII),
        )
        out.write(slice)
        out.flush()
    }
}
