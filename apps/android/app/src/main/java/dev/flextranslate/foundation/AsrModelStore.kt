package dev.flextranslate.foundation

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * On-device model store. Resolves where streaming-ASR model files live and reports their honest
 * installed state. Weights are NOT bundled in the APK (license/size) — they arrive via
 * first-run download (or, for the device demo, an `adb push` into the same directory).
 *
 * Layout: `<externalFilesDir>/models/<modelId>/<file>` which on the device resolves to
 * `/sdcard/Android/data/dev.flextranslate/files/models/<modelId>/`.
 */
class AsrModelStore(private val context: Context) {

    /**
     * Candidate `models/` roots in resolution order. Internal [Context.getFilesDir] is checked
     * first: it is always readable by the app with no scoped-storage FUSE/mount-namespace caveat,
     * so a model placed there (first-run download, or an `adb`/`run-as` copy for the device demo)
     * is reliably visible. External files dir is the secondary location.
     */
    private fun modelRoots(): List<File> = buildList {
        add(File(context.filesDir, MODELS_DIR))
        context.getExternalFilesDir(null)?.let { add(File(it, MODELS_DIR)) }
    }

    /** Primary writable `models/` root (internal), created on demand. */
    fun modelsRoot(): File = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    /**
     * Directory for [spec]: the first candidate root that already contains the model, else the
     * primary writable root (so downloads land in a stable place).
     */
    fun modelDir(spec: AsrModelSpec): File {
        modelRoots().forEach { root ->
            val dir = File(root, spec.modelId)
            if (spec.isInstalled(dir)) return dir
        }
        return File(modelsRoot(), spec.modelId)
    }

    fun isInstalled(spec: AsrModelSpec): Boolean = spec.isInstalled(modelDir(spec))

    /** Per-file install report — used by the Models screen to show size/checksum honestly. */
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
     * Inspect a model's files. [withChecksum] hashes each present file (expensive for the 144 MB
     * RU model), so callers default it off for UI and on for evidence capture.
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
