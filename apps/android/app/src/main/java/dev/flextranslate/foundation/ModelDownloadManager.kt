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
 * Реальная in-app загрузка моделей. Тащит файлы пака с их URL в тот же внутренний корень
 * `filesDir/models/<modelId>/`, который читают [AsrModelStore] / [MtModelStore] — так завершённая
 * загрузка сразу видна рантайму, без bundling в APK и без `adb push`.
 *
 * На каждый пак отдаёт наблюдаемое [DownloadState] (Compose [State]), которое рисует экран моделей:
 * idle / downloading (с суммарным %/байтами) / done / failed / cancelled. Загрузка идёт в фоновом
 * потоке; вся тяжёлая механика сеть/диск/проверка — в [ModelDownloadEngine].
 *
 * Только онлайн: [isOnline] честно отказывает старту, если сети нет. Идемпотентно: уже проверенный
 * пак сразу даёт [DownloadState.Done].
 */
class ModelDownloadManager(
    context: Context,
    private val resolveModelDir: (modelId: String) -> File,
    private val engine: ModelDownloadEngine = ModelDownloadEngine(),
) {

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Наблюдаемое состояние на пак, создаётся лениво — UI может дёрнуть `getState(id)` для любого пака. */
    private val states = ConcurrentHashMap<String, androidx.compose.runtime.MutableState<DownloadState>>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /** Куда кладётся загрузка и откуда удаляет delete — разрешённый каталог модели. */
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

    /** True, когда есть рабочее, прошедшее валидацию сетевое соединение. */
    fun isOnline(): Boolean {
        val cm = connectivity ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Наблюдаемое состояние для [modelId]; по умолчанию [DownloadState.Idle]. */
    fun state(modelId: String): State<DownloadState> = stateHolder(modelId)

    private fun stateHolder(modelId: String) =
        states.getOrPut(modelId) { mutableStateOf(DownloadState.Idle) }

    fun isDownloading(modelId: String): Boolean =
        stateHolder(modelId).value is DownloadState.Downloading

    /**
     * Запустить (или возобновить) загрузку [modelId]. Ничего не делает, если уже качается. Честно
     * отказывает, если нет сети или для id нет спеки загрузки. Работает в фоновом потоке.
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
                    // Не затираем терминальное состояние запоздавшим тиком прогресса после отмены.
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

    /** Кооперативно отменить идущую загрузку. Частичный `.part` оставляем — пригодится для возобновления. */
    fun cancel(modelId: String) {
        cancelFlags[modelId]?.set(true)
    }

    /** Вернуть терминальный (done/failed/cancelled) пак в idle, чтобы UI снова предложил загрузку. */
    fun reset(modelId: String) {
        val holder = stateHolder(modelId)
        if (holder.value !is DownloadState.Downloading) holder.value = DownloadState.Idle
    }

    /**
     * Удалить файлы установленного пака (и оставшиеся `.part`), освободив место. Отказывает, пока идёт
     * загрузка. Возвращает true, если каталога после этого больше нет.
     */
    fun delete(modelId: String): Boolean {
        if (isDownloading(modelId)) return false
        val dir = resolveModelDir(modelId)
        val removed = dir.deleteRecursively()
        stateHolder(modelId).value = DownloadState.Idle
        return removed && !dir.exists()
    }
}
