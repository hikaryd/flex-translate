package dev.flextranslate.foundation

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure, Android-free download engine for one model pack. Owns the real network and disk mechanics so
 * they can be unit-tested deterministically against a local HTTP server:
 *
 *  - **Resume**: a partial `<file>.part` is continued with an HTTP `Range: bytes=<have>-` request;
 *    a server honouring it (206) appends, otherwise (200) the engine restarts that file cleanly.
 *  - **Atomic install**: bytes land in `<file>.part`, are SHA-256 verified, then `renameTo`d to the
 *    final name only on a verified match — a reader never sees a half-written or corrupt file.
 *  - **Rollback**: on size/checksum mismatch or I/O error the `.part` is deleted so a retry starts
 *    clean (a Range resume off a corrupt tail would never converge).
 *  - **Idempotent**: a file already present, correctly sized AND checksum-verified is skipped.
 *  - **Cancellable**: a shared [AtomicBoolean] is polled between chunks; cancel leaves the `.part`
 *    in place so the next attempt resumes rather than refetching.
 *
 * The engine never touches connectivity or UI — [ModelDownloadManager] layers those on top.
 */
class ModelDownloadEngine(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    /** Aggregate progress across the pack's files. [bytesDone] includes already-installed files. */
    data class Progress(
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentFile: String?,
    ) {
        val fraction: Float
            get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
    }

    /** Terminal outcome of a pack download. */
    sealed interface Result {
        data object Success : Result
        data object Cancelled : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Download every file in [spec] into [modelDir], emitting [onProgress] as bytes accumulate.
     * [cancelled] is polled cooperatively. Returns a terminal [Result]; never throws for expected
     * network/IO/verification failures (those become [Result.Failure]).
     */
    fun download(
        spec: ModelDownloadSpec,
        modelDir: File,
        cancelled: AtomicBoolean,
        onProgress: (Progress) -> Unit,
    ): Result {
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            return Result.Failure("Не удалось создать каталог модели: ${modelDir.path}")
        }
        val total = spec.totalBytes
        // Files verified-present on entry count toward progress immediately (idempotent resume).
        var completedBytes = spec.files.sumOf { file ->
            if (isInstalledAndVerified(File(modelDir, file.fileName), file)) file.sizeBytes else 0L
        }
        onProgress(Progress(completedBytes, total, null))

        for (file in spec.files) {
            if (cancelled.get()) return Result.Cancelled
            val target = File(modelDir, file.fileName)
            if (isInstalledAndVerified(target, file)) continue

            val baseBytes = completedBytes
            val result = downloadFile(file, target, cancelled) { fileBytes ->
                onProgress(Progress(baseBytes + fileBytes, total, file.fileName))
            }
            when (result) {
                is FileResult.Done -> completedBytes = baseBytes + file.sizeBytes
                FileResult.Cancelled -> return Result.Cancelled
                is FileResult.Failed -> return Result.Failure(result.message)
            }
            onProgress(Progress(completedBytes, total, null))
        }
        return Result.Success
    }

    private sealed interface FileResult {
        data object Done : FileResult
        data object Cancelled : FileResult
        data class Failed(val message: String) : FileResult
    }

    private fun downloadFile(
        file: ModelFileDownload,
        target: File,
        cancelled: AtomicBoolean,
        onFileBytes: (Long) -> Unit,
    ): FileResult {
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        // A stale .part larger than the expected size is a corrupt tail — drop it before resuming.
        if (part.exists() && part.length() > file.sizeBytes) part.delete()
        val existing = if (part.isFile) part.length() else 0L

        val connection = try {
            openConnection(file.sourceUrl, existing)
        } catch (io: IOException) {
            return FileResult.Failed("Сеть недоступна: ${io.message ?: file.fileName}")
        }

        return try {
            val status = connection.responseCode
            val resuming = existing > 0L && status == HttpURLConnection.HTTP_PARTIAL
            // Server ignored the Range header → restart this file from zero, do not append.
            val startFrom = if (resuming) existing else 0L
            if (startFrom == 0L && part.exists()) part.delete()
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                return FileResult.Failed("HTTP $status для ${file.fileName}")
            }
            streamToPart(connection, part, file, startFrom, cancelled, onFileBytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun streamToPart(
        connection: HttpURLConnection,
        part: File,
        file: ModelFileDownload,
        startFrom: Long,
        cancelled: AtomicBoolean,
        onFileBytes: (Long) -> Unit,
    ): FileResult {
        var written = startFrom
        try {
            connection.inputStream.use { input ->
                appendTo(part, startFrom > 0L).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (cancelled.get()) return FileResult.Cancelled
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onFileBytes(written)
                    }
                }
            }
        } catch (io: IOException) {
            // Network drop mid-stream: keep the .part so a later attempt resumes from here.
            return FileResult.Failed("Ошибка загрузки ${file.fileName}: ${io.message ?: "I/O"}")
        }

        if (written != file.sizeBytes) {
            part.delete() // size mismatch → corrupt, roll back so retry starts clean.
            return FileResult.Failed("Размер ${file.fileName} не совпал ($written≠${file.sizeBytes})")
        }
        val actual = sha256Of(part)
        if (!actual.equals(file.sha256, ignoreCase = true)) {
            part.delete() // checksum mismatch → corrupt, roll back.
            return FileResult.Failed("Контрольная сумма ${file.fileName} не совпала")
        }
        val target = File(part.parentFile, part.name.removeSuffix(PART_SUFFIX))
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return FileResult.Failed("Не удалось переименовать ${file.fileName}")
        }
        return FileResult.Done
    }

    /** A file counts as installed only when present, correctly sized, and checksum-verified. */
    private fun isInstalledAndVerified(target: File, file: ModelFileDownload): Boolean =
        target.isFile && target.length() == file.sizeBytes &&
            sha256Of(target).equals(file.sha256, ignoreCase = true)

    private fun openConnection(url: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        if (resumeFrom > 0L) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        connection.connect()
        return connection
    }

    private fun appendTo(file: File, append: Boolean) = java.io.FileOutputStream(file, append)

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> digestStream(input, digest) }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun digestStream(input: InputStream, digest: MessageDigest) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val BUFFER_BYTES = 1 shl 16
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000
    }
}
