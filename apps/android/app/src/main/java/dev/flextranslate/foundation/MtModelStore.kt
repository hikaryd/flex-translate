package dev.flextranslate.foundation

import android.content.Context
import java.io.File

/**
 * On-device store for machine-translation model files. Mirrors [AsrModelStore]: weights are NOT
 * bundled in the APK (size); they arrive via first-run download or an `adb push` for the device
 * demo, into the same internal `models/<modelId>/` root the ASR packs use.
 */
class MtModelStore(private val context: Context) {

    /** Candidate `models/` roots in resolution order — internal first (no scoped-storage caveat). */
    private fun modelRoots(): List<File> = buildList {
        add(File(context.filesDir, MODELS_DIR))
        context.getExternalFilesDir(null)?.let { add(File(it, MODELS_DIR)) }
    }

    /** Primary writable `models/` root (internal), created on demand. */
    fun modelsRoot(): File = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    /** Directory for [spec]: first root that already contains it, else the primary writable root. */
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

    /** Honest per-file install report for the Models screen. No checksum (large files). */
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
