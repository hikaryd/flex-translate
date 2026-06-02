package dev.flextranslate.foundation

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Чистый, без Android, движок загрузки одного пака модели. Держит всю реальную работу с сетью и
 * диском, чтобы её можно было детерминированно гонять в юнит-тестах против локального HTTP-сервера:
 *
 *  - **Докачка**: недокачанный `<file>.part` продолжаем запросом `Range: bytes=<have>-`; если сервер
 *    его уважает (206) — дописываем, иначе (200) перекачиваем файл с нуля.
 *  - **Атомарная установка**: байты пишутся в `<file>.part`, проверяются по SHA-256 и только при
 *    совпадении `renameTo` в финальное имя — читатель никогда не видит недописанный или битый файл.
 *  - **Откат**: при несовпадении размера/чек-суммы или ошибке I/O `.part` удаляется, чтобы ретрай
 *    стартовал с чистого листа (докачка Range с битого хвоста никогда не сошлась бы).
 *  - **Идемпотентность**: уже лежащий файл с верным размером И чек-суммой пропускаем.
 *  - **Отменяемость**: общий [AtomicBoolean] опрашивается между чанками; отмена оставляет `.part`
 *    на месте, чтобы следующая попытка докачала, а не качала заново.
 *
 * Движок не лезет в connectivity и UI — это надстраивает поверх [ModelDownloadManager].
 */
class ModelDownloadEngine(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    /** Суммарный прогресс по файлам пака. [bytesDone] включает уже установленные файлы. */
    data class Progress(
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentFile: String?,
    ) {
        val fraction: Float
            get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
    }

    /** Итоговый исход загрузки пака. */
    sealed interface Result {
        data object Success : Result
        data object Cancelled : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Качает каждый файл из [spec] в [modelDir], дёргая [onProgress] по мере накопления байт.
     * [cancelled] опрашивается кооперативно. Возвращает терминальный [Result]; на ожидаемых
     * сбоях сети/IO/проверки не бросает исключений — они становятся [Result.Failure].
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
        // Файлы, уже подтверждённые на входе, сразу идут в зачёт прогресса (идемпотентная докачка).
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
        // Залежавшийся .part больше ожидаемого размера — битый хвост; сносим перед докачкой.
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
            // Сервер проигнорил Range → качаем файл с нуля, не дописываем.
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
            // Сеть оборвалась посреди потока: оставляем .part, чтобы потом докачать отсюда.
            return FileResult.Failed("Ошибка загрузки ${file.fileName}: ${io.message ?: "I/O"}")
        }

        if (written != file.sizeBytes) {
            part.delete() // размер не сошёлся → битый, откатываем, чтобы ретрай был с чистого листа.
            return FileResult.Failed("Размер ${file.fileName} не совпал ($written≠${file.sizeBytes})")
        }
        val actual = sha256Of(part)
        if (!actual.equals(file.sha256, ignoreCase = true)) {
            part.delete() // чек-сумма не сошлась → битый, откатываем.
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

    /** Файл считается установленным только если он есть, верного размера и прошёл чек-сумму. */
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
