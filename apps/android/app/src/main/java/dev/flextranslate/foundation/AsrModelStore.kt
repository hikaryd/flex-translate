package dev.flextranslate.foundation

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Стор моделей на устройстве. Находит, где лежат файлы потоковой ASR-модели, и честно сообщает,
 * установлены ли они. Веса в APK НЕ зашиты (лицензия/размер) — приезжают первой загрузкой (или,
 * для демо на устройстве, через `adb push` в тот же каталог).
 *
 * Раскладка: `<externalFilesDir>/models/<modelId>/<file>`, что на устройстве разворачивается в
 * `/sdcard/Android/data/dev.flextranslate/files/models/<modelId>/`.
 */
class AsrModelStore(private val context: Context) {

    /**
     * Кандидаты-корни `models/` в порядке поиска. Сначала смотрим внутренний [Context.getFilesDir]:
     * он всегда читается приложением без оговорок scoped-storage про FUSE/mount-namespace, поэтому
     * модель, положенная туда (первой загрузкой или копией через `adb`/`run-as` для демо), видна
     * надёжно. External files dir — запасной вариант.
     */
    private fun modelRoots(): List<File> = buildList {
        add(File(context.filesDir, MODELS_DIR))
        context.getExternalFilesDir(null)?.let { add(File(it, MODELS_DIR)) }
    }

    /** Основной корень `models/` для записи (внутренний), создаётся по требованию. */
    fun modelsRoot(): File = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    /**
     * Каталог для [spec]: первый корень-кандидат, где модель уже лежит, иначе основной корень для
     * записи (чтобы загрузки приземлялись в стабильное место).
     */
    fun modelDir(spec: AsrModelSpec): File {
        modelRoots().forEach { root ->
            val dir = File(root, spec.modelId)
            if (spec.isInstalled(dir)) return dir
        }
        return File(modelsRoot(), spec.modelId)
    }

    fun isInstalled(spec: AsrModelSpec): Boolean = spec.isInstalled(modelDir(spec))

    /** Пофайловый отчёт об установке — экран «Модели» по нему честно показывает размер/контрольную сумму. */
    data class FileStatus(
        val name: String,
        val present: Boolean,
        val sizeBytes: Long,
        val sha256: String?,
    )

    data class InstallReport(
        val modelId: String,
        val installed: Boolean,
        val totalSizeBytes: Long,
        val files: List<FileStatus>,
    ) {
        val totalSizeMb: Double get() = totalSizeBytes.toDouble() / BYTES_PER_MB
    }

    /**
     * Осматривает файлы модели. [withChecksum] хеширует каждый присутствующий файл (для RU-модели
     * на 144 МБ это дорого), поэтому в UI его по умолчанию выключают, а для сбора пруфов включают.
     */
    fun inspect(spec: AsrModelSpec, withChecksum: Boolean = false): InstallReport {
        val dir = modelDir(spec)
        val files = spec.requiredFiles.map { name ->
            val file = File(dir, name)
            val present = file.isFile && file.length() > 0L
            FileStatus(
                name = name,
                present = present,
                sizeBytes = if (present) file.length() else 0L,
                sha256 = if (present && withChecksum) sha256Of(file) else null,
            )
        }
        return InstallReport(
            modelId = spec.modelId,
            installed = files.all { it.present },
            totalSizeBytes = files.sumOf { it.sizeBytes },
            files = files,
        )
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val MODELS_DIR = "models"
        private const val BUFFER_BYTES = 1 shl 16
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
