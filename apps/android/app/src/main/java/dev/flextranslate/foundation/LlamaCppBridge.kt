package dev.flextranslate.foundation

import android.util.Log

/**
 * Thin Kotlin facade over the llama.cpp JNI shim (`libmilmmt_jni.so`, built from the vendored
 * llama.cpp under `app/src/main/cpp/`). Each method maps 1:1 to a native function and carries an
 * opaque session handle (a `MilmmtSession*` reinterpreted as a Long).
 *
 * Real model output only: [generate] returns the genuine decoded token stream, or null on any
 * native failure (the caller gates honestly — never a fabricated translation). The native library
 * is loaded lazily and at most once; if it is absent (e.g. an ABI without the build) [isAvailable]
 * is false and callers gate.
 */
object LlamaCppBridge {

    private const val TAG = "LlamaCppBridge"

    private val loadResult: Result<Unit> by lazy {
        runCatching {
            // b9453 prebuilt: libggml-cpu-*.so have no embedded SONAME so they cannot be a
            // static DT_NEEDED of libmilmmt_jni.so. Load them explicitly here before the JNI
            // shim so ggml_backend_cpu_reg() is already in the process's symbol table when
            // ensure_backend() calls it. Load all variants — the runtime picks the best one.
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

    /** True when the native llama.cpp bridge loaded successfully on this device/ABI. */
    val isAvailable: Boolean get() = loadResult.isSuccess

    /**
     * Load a GGUF model from [path]. Returns an opaque handle (> 0) or 0 on failure. CPU-only.
     * [nThreads] generation threads; [nCtx] context window in tokens.
     */
    fun load(path: String, nThreads: Int, nCtx: Int): Long {
        if (!isAvailable) return 0L
        return runCatching { nativeLoad(path, nThreads, nCtx) }.getOrElse { t ->
            Log.e(TAG, "nativeLoad failed", t)
            0L
        }
    }

    /**
     * Run one greedy completion for [prompt] (up to [maxNewTokens] new tokens) on the model behind
     * [handle]. Returns the decoded answer (genuine model output) or null on failure.
     */
    fun generate(handle: Long, prompt: String, maxNewTokens: Int): String? {
        if (!isAvailable || handle == 0L) return null
        return runCatching { nativeGenerate(handle, prompt, maxNewTokens) }.getOrElse { t ->
            Log.e(TAG, "nativeGenerate failed", t)
            null
        }
    }

    /** Free the model + context behind [handle]. Safe to call with 0. */
    fun free(handle: Long) {
        if (!isAvailable || handle == 0L) return
        runCatching { nativeFree(handle) }.onFailure { t -> Log.e(TAG, "nativeFree failed", t) }
    }

    private external fun nativeLoad(path: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxNewTokens: Int): String?
    private external fun nativeFree(handle: Long)
}
