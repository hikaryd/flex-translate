package dev.flextranslate.foundation

import android.content.Context
import java.io.File

/**
 * Хранилище файлов MT-моделей на устройстве. Работает по тому же принципу, что [AsrModelStore]:
 * веса в APK не кладём (большие), они появляются при первой загрузке или через `adb push` для
 * демо на устройстве — в тот же внутренний корень `models/<modelId>/`, что и пакеты ASR.
 */
class MtModelStore(private val context: Context) {

    /** Кандидаты на корень `models/` в порядке поиска — сначала внутренний (без возни со scoped storage). */
    private fun modelRoots(): List<File> = buildList {
        add(File(context.filesDir, MODELS_DIR))
        context.getExternalFilesDir(null)?.let { add(File(it, MODELS_DIR)) }
    }

    /** Основной корень `models/` для записи (внутренний), создаётся по требованию. */
    fun modelsRoot(): File = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    /** Каталог для [spec]: первый корень, где модель уже лежит, иначе — основной корень для записи. */
    fun modelDir(spec: MtModelSpec): File {
        modelRoots().forEach { root ->
            val dir = File(root, spec.modelId)
            if (spec.isInstalled(dir)) return dir
        }
        return File(modelsRoot(), spec.modelId)
    }

    fun isInstalled(spec: MtModelSpec): Boolean = spec.isInstalled(modelDir(spec))

    data class FileStatus(val name: String, val present: Boolean, val sizeBytes: Long)

    data class InstallReport(
        val modelId: String,
        val installed: Boolean,
        val totalSizeBytes: Long,
        val files: List<FileStatus>,
    ) {
        val totalSizeMb: Double get() = totalSizeBytes.toDouble() / BYTES_PER_MB
    }

    /** Честный отчёт по каждому файлу для экрана моделей. Без контрольной суммы (файлы большие). */
    fun inspect(spec: MtModelSpec): InstallReport {
        val dir = modelDir(spec)
        val files = spec.requiredFiles.map { name ->
            val file = File(dir, name)
            val present = file.isFile && file.length() > 0L
            FileStatus(name = name, present = present, sizeBytes = if (present) file.length() else 0L)
        }
        return InstallReport(
            modelId = spec.modelId,
            installed = files.all { it.present },
            totalSizeBytes = files.sumOf { it.sizeBytes },
            files = files,
        )
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
