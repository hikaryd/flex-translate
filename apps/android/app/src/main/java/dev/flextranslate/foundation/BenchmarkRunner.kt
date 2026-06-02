package dev.flextranslate.foundation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only deterministic benchmark harness. Triggered by launching the app with the intent extra
 * `-e BENCH 1` (e.g. via `adb shell am start -n dev.flextranslate/.foundation.MainActivity -e BENCH 1`).
 *
 * For each registered engine × sentence pair it measures:
 *  - model load time (first translation, ms)
 *  - per-translation latency (ms)
 *  - genuine output text (NEVER fabricated — if the engine is unavailable or the model is absent
 *    the result field records the honest failure reason instead of fake text)
 *
 * Results are emitted to logcat under the tag [TAG] in a tab-delimited format that is grep-friendly:
 *   FlexBench  engine=<id>  pair=<src->tgt>  input=<text>  output=<text>  latency_ms=<N>  load_ms=<N>
 *
 * This is debug-only code — it is not reachable in release builds and adds zero overhead to
 * normal app operation. It does NOT write to disk, modify UI state, or interact with the audio
 * pipeline; it only calls the translation providers directly and logs.
 */
object BenchmarkRunner {

    const val TAG = "FlexBench"
    const val INTENT_EXTRA = "BENCH"

    /** Fixed sentence set for deterministic cross-engine comparison. */
    private val SENTENCES = listOf(
        Sentence("ru->en", "Привет, как дела?"),
        Sentence("ru->en", "Я хочу заказать такси до аэропорта."),
        Sentence("ru->en", "Спасибо за вашу помощь."),
        Sentence("en->ru", "Good morning, what time is breakfast?"),
        Sentence("en->ru", "Please call an ambulance immediately."),
        Sentence("zh->ru", "你好，请问洗手间在哪里？"),
    )

    data class Sentence(val pair: String, val text: String)

    data class Result(
        val engine: String,
        val pair: String,
        val input: String,
        val output: String,          // genuine model output or honest failure description
        val latencyMs: Long,
        val loadMs: Long,            // 0 for subsequent calls after model is loaded
        val isError: Boolean,        // true when output is a failure reason, not a translation
    )

    /**
     * Run benchmarks for all available engines, returning a list of [Result].
     * Must be called from a coroutine (dispatches to [Dispatchers.IO]).
     * Logs every result to logcat under [TAG].
     */
    suspend fun run(context: Context): List<Result> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Result>()
        val mtStore = MtModelStore(context)

        results += benchmarkM2m100(mtStore)
        results += benchmarkMilmmt(mtStore)

        Log.i(TAG, "=== FlexBench complete: ${results.size} results ===")
        results
    }

    // ── M2M-100 ─────────────────────────────────────────────────────────────────────────────────

    private fun benchmarkM2m100(mtStore: MtModelStore): List<Result> {
        val spec = MtModelSpecs.M2M100_418M
        val modelDir = mtStore.modelDir(spec)
        val provider = M2m100MtProvider(spec, modelDir)
        val results = mutableListOf<Result>()
        var firstCall = true

        for (sentence in SENTENCES) {
            val direction = MtDirection.parse(sentence.pair) ?: continue  // skip unsupported pairs
            val t0 = System.currentTimeMillis()
            val tr = provider.translate(sentence.text, sentence.pair, "mid")
            val elapsed = System.currentTimeMillis() - t0

            val loadMs = if (firstCall) elapsed else 0L
            firstCall = false

            val output = tr.text ?: ("ERROR: ${tr.unsupportedReason ?: "null"}")
            val isError = tr.text == null

            val r = Result(
                engine = provider.providerId,
                pair = sentence.pair,
                input = sentence.text,
                output = output,
                latencyMs = elapsed,
                loadMs = loadMs,
                isError = isError,
            )
            results += r
            logResult(r)
        }

        provider.close()
        return results
    }

    // ── MiLMMT ──────────────────────────────────────────────────────────────────────────────────

    private fun benchmarkMilmmt(mtStore: MtModelStore): List<Result> {
        val spec = MtModelSpecs.MILMMT_46_4B_Q6
        val modelDir = mtStore.modelDir(spec)
        val provider = MilmmtMtProvider(spec, modelDir)
        val results = mutableListOf<Result>()
        var firstCall = true

        for (sentence in SENTENCES) {
            val t0 = System.currentTimeMillis()
            val tr = provider.translate(sentence.text, sentence.pair, "high")
            val elapsed = System.currentTimeMillis() - t0

            val loadMs = if (firstCall) elapsed else 0L
            firstCall = false

            val output = tr.text ?: ("ERROR: ${tr.unsupportedReason ?: "null"}")
            val isError = tr.text == null

            val r = Result(
                engine = provider.providerId,
                pair = sentence.pair,
                input = sentence.text,
                output = output,
                latencyMs = elapsed,
                loadMs = loadMs,
                isError = isError,
            )
            results += r
            logResult(r)
        }

        provider.close()
        return results
    }

    // ── logging ─────────────────────────────────────────────────────────────────────────────────

    private fun logResult(r: Result) {
        val level = if (r.isError) Log.WARN else Log.INFO
        Log.println(
            level, TAG,
            "engine=${r.engine}\tpair=${r.pair}\tinput=${r.input}\t" +
                "output=${r.output}\tlatency_ms=${r.latencyMs}\tload_ms=${r.loadMs}",
        )
    }
}
