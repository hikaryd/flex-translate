package dev.flextranslate.foundation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real in-app model download manager. Acquires a pack's files from their source URLs into the same
 * internal `filesDir/models/<modelId>/` root the [AsrModelStore] / [MtModelStore] resolve, so a
 * completed download is immediately visible to the runtime — no APK bundling, no `adb push`.
 *
 * Per pack it exposes an observable [DownloadState] (Compose [State]) the Models screen renders:
 * idle / downloading (with aggregate %/bytes) / done / failed / cancelled. Downloads run on a
 * background thread; the heavy network/disk/verify mechanics live in [ModelDownloadEngine].
 *
 * Online-only: [isOnline] gates start with an honest refusal when offline. Idempotent: a
 * verified-present pack short-circuits to [DownloadState.Done].
 */
class ModelDownloadManager(
    context: Context,
    private val resolveModelDir: (modelId: String) -> File,
    private val engine: ModelDownloadEngine = ModelDownloadEngine(),
) {

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Per-pack observable state, lazily created so the UI can `getState(id)` for any pack. */
    private val states = ConcurrentHashMap<String, androidx.compose.runtime.MutableState<DownloadState>>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /** Where a download lands / a delete removes — the model's resolved directory. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val bytesDone: Long, val bytesTotal: Long, val currentFile: String?) : DownloadState {
            val fraction: Float
                get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
        }
        data object Done : DownloadState
        data object Cancelled : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    /** True when a usable validated network connection exists. */
    fun isOnline(): Boolean {
        val cm = connectivity ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Observable state for [modelId]; defaults to [DownloadState.Idle]. */
    fun state(modelId: String): State<DownloadState> = stateHolder(modelId)

    private fun stateHolder(modelId: String) =
        states.getOrPut(modelId) { mutableStateOf(DownloadState.Idle) }

    fun isDownloading(modelId: String): Boolean =
        stateHolder(modelId).value is DownloadState.Downloading

    /**
     * Start (or resume) the download for [modelId]. No-op if already downloading. Refuses honestly
     * when offline or when the model id has no download spec. Runs on a background thread.
     */
    fun start(modelId: String) {
        val spec = ModelDownloadSpecs.forModelId(modelId) ?: run {
            stateHolder(modelId).value = DownloadState.Failed("Нет источника загрузки для $modelId")
            return
        }
        val holder = stateHolder(modelId)
        if (holder.value is DownloadState.Downloading) return
        if (!isOnline()) {
            holder.value = DownloadState.Failed("Нет сети — загрузка доступна только онлайн")
            return
        }
        val cancelFlag = AtomicBoolean(false)
        cancelFlags[modelId] = cancelFlag
        holder.value = DownloadState.Downloading(0L, spec.totalBytes, null)

        Thread({
            val result = engine.download(
                spec = spec,
                modelDir = resolveModelDir(modelId),
                cancelled = cancelFlag,
                onProgress = { progress ->
                    // Don't clobber a terminal state with a late progress tick after cancel.
                    if (holder.value is DownloadState.Downloading) {
                        holder.value = DownloadState.Downloading(
                            bytesDone = progress.bytesDone,
                            bytesTotal = progress.bytesTotal,
                            currentFile = progress.currentFile,
                        )
                    }
                },
            )
            holder.value = when (result) {
                ModelDownloadEngine.Result.Success -> DownloadState.Done
                ModelDownloadEngine.Result.Cancelled -> DownloadState.Cancelled
                is ModelDownloadEngine.Result.Failure -> DownloadState.Failed(result.message)
            }
            cancelFlags.remove(modelId)
        }, "flex-model-dl-$modelId").start()
    }

    /** Cooperatively cancel an in-flight download. The partial `.part` is kept for a later resume. */
    fun cancel(modelId: String) {
        cancelFlags[modelId]?.set(true)
    }

    /** Reset a terminal (done/failed/cancelled) pack back to idle so the UI can offer download again. */
    fun reset(modelId: String) {
        val holder = stateHolder(modelId)
        if (holder.value !is DownloadState.Downloading) holder.value = DownloadState.Idle
    }

    /**
     * Delete an installed pack's files (and any leftover `.part`s), freeing storage. Refuses while a
     * download is in flight. Returns true when the directory no longer exists afterwards.
     */
    fun delete(modelId: String): Boolean {
        if (isDownloading(modelId)) return false
        val dir = resolveModelDir(modelId)
        val removed = dir.deleteRecursively()
        stateHolder(modelId).value = DownloadState.Idle
        return removed && !dir.exists()
    }
}
