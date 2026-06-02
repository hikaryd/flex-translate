package dev.flextranslate.foundation

import android.util.Log

/**
 * Тонкая Kotlin-обёртка над JNI-шимом llama.cpp (`libmilmmt_jni.so`, собран из вендоренного
 * llama.cpp в `app/src/main/cpp/`). Каждый метод — это ровно одна нативная функция; хэндл сессии
 * непрозрачный (`MilmmtSession*`, переинтерпретированный в Long).
 *
 * Отдаём только реальный вывод модели: [generate] возвращает настоящий поток декодированных токенов
 * либо null при любом сбое в native — выдуманный перевод не подсовываем, вызывающий код честно
 * гейтит сам. Либу грузим лениво и не больше одного раза; если её нет (например, ABI без сборки),
 * [isAvailable] == false и вызывающие гейтят.
 */
object LlamaCppBridge {

    private const val TAG = "LlamaCppBridge"

    private val loadResult: Result<Unit> by lazy {
        runCatching {
            // В prebuilt b9453 у libggml-cpu-*.so нет вшитого SONAME, поэтому их нельзя
            // прописать статическим DT_NEEDED у libmilmmt_jni.so. Грузим их вручную ДО JNI-шима,
            // чтобы ggml_backend_cpu_reg() уже лежал в таблице символов к моменту вызова
            // ensure_backend(). Грузим все варианты — рантайм сам выберет подходящий.
            for (variant in listOf(
                "ggml-cpu-android_armv8.0_1",
                "ggml-cpu-android_armv8.2_1",
                "ggml-cpu-android_armv8.2_2",
                "ggml-cpu-android_armv8.6_1",
                "ggml-cpu-android_armv9.0_1",
                "ggml-cpu-android_armv9.2_1",
                "ggml-cpu-android_armv9.2_2",
            )) {
                runCatching { System.loadLibrary(variant) }
                    .onSuccess { Log.i(TAG, "loaded $variant") }
                    .onFailure { Log.d(TAG, "skip $variant: ${it.message}") }
            }
            System.loadLibrary("milmmt_jni")
            Log.i(TAG, "libmilmmt_jni loaded")
            Unit
        }.onFailure { t -> Log.e(TAG, "failed to load libmilmmt_jni", t) }
    }

    /** true, если нативный мост llama.cpp поднялся на этом устройстве/ABI. */
    val isAvailable: Boolean get() = loadResult.isSuccess

    /**
     * Грузит GGUF-модель из [path]. Вернёт непрозрачный хэндл (> 0) либо 0 при сбое. Только CPU.
     * [nThreads] — число потоков генерации; [nCtx] — размер контекста в токенах.
     */
    fun load(path: String, nThreads: Int, nCtx: Int): Long {
        if (!isAvailable) return 0L
        return runCatching { nativeLoad(path, nThreads, nCtx) }.getOrElse { t ->
            Log.e(TAG, "nativeLoad failed", t)
            0L
        }
    }

    /**
     * Один greedy-проход по [prompt] (не больше [maxNewTokens] новых токенов) на модели за [handle].
     * Вернёт декодированный ответ (настоящий вывод модели) либо null при сбое.
     */
    fun generate(handle: Long, prompt: String, maxNewTokens: Int): String? {
        if (!isAvailable || handle == 0L) return null
        return runCatching { nativeGenerate(handle, prompt, maxNewTokens) }.getOrElse { t ->
            Log.e(TAG, "nativeGenerate failed", t)
            null
        }
    }

    /** Освобождает модель и контекст за [handle]. С 0 вызывать безопасно. */
    fun free(handle: Long) {
        if (!isAvailable || handle == 0L) return
        runCatching { nativeFree(handle) }.onFailure { t -> Log.e(TAG, "nativeFree failed", t) }
    }

    private external fun nativeLoad(path: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxNewTokens: Int): String?
    private external fun nativeFree(handle: Long)
}
