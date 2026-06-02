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
 * Gemini BYOK test path: triggered by `-e GEMINI_TEST 1`. Reads the key from [AndroidGeminiKeyStore],
 * calls [GeminiDirectClient] directly (no CloudCallGate — debug harness only), and logs the real
 * translated output and latency. The key is NEVER logged or persisted by this code.
 *
 * This is debug-only code — it is not reachable in release builds and adds zero overhead to
 * normal app operation. It does NOT write to disk, modify UI state, or interact with the audio
 * pipeline; it only calls the translation providers directly and logs.
 */
object BenchmarkRunner {

    const val TAG = "FlexBench"
    const val INTENT_EXTRA = "BENCH"
    const val INTENT_EXTRA_GEMINI = "GEMINI_TEST"

    /**
     * Debug-only: provision a Gemini API key into [AndroidGeminiKeyStore] via intent extra.
     * Triggered by `-e SET_GEMINI_KEY <key>`. The key value travels ONLY to secure storage;
     * it is NEVER logged. This path exists solely to avoid IME/clipboard issues in the device lab.
     */
    const val INTENT_EXTRA_SET_GEMINI_KEY = "SET_GEMINI_KEY"

    /** Fixed sentence set for deterministic cross-engine comparison. */
    private val SENTENCES = listOf(
        Sentence("ru->en", "Привет, как дела?"),
        Sentence("ru->en", "Я хочу заказать такси до аэропорта."),
        Sentence("ru->en", "Спасибо за вашу помощь."),
        Sentence("en->ru", "Good morning, what time is breakfast?"),
        Sentence("en->ru", "Please call an ambulance immediately."),
        Sentence("zh->ru", "你好，请问洗手间在哪里？"),
    )

    /** Single sentence used for the Gemini BYOK latency test. */
    private val GEMINI_TEST_SENTENCE = Sentence("ru->en", "Привет, как дела?")

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

    /**
     * Debug-only: provision a Gemini API key into [AndroidGeminiKeyStore].
     * Triggered by `-e SET_GEMINI_KEY <key>`. The key is saved to encrypted storage and
     * NEVER logged. Log only confirms success/failure without key value.
     */
    fun provisionGeminiKey(context: Context, apiKey: String) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "SET_GEMINI_KEY: key is blank — skipped")
            return
        }
        val keyStore = AndroidGeminiKeyStore(context)
        keyStore.saveKey(apiKey)
        // Log confirmation only — NEVER log the key value itself.
        Log.i(TAG, "SET_GEMINI_KEY: key provisioned to GeminiKeyStore (${apiKey.length} chars)")
    }

    /**
     * Run the Gemini BYOK direct-call test. Triggered by `-e GEMINI_TEST 1`.
     *
     * Reads the API key from [AndroidGeminiKeyStore] (EncryptedSharedPreferences).
     * Calls [GeminiDirectClient] directly — this is a debug harness; [CloudCallGate] consent
     * checks are intentionally bypassed here (the key was provisioned explicitly for this test).
     *
     * Security: the key is fetched just-in-time and passed only to [GeminiDirectClient.translate].
     * It is NEVER logged, NEVER included in any log message, NEVER written to any file.
     */
    suspend fun runGeminiTest(context: Context): Result = withContext(Dispatchers.IO) {
        val keyStore = AndroidGeminiKeyStore(context)
        val config = GeminiFlashConfig(
            credentialMode = GeminiCredentialMode.OWN_KEY,
        )
        val client = GeminiDirectClient(config)
        val sentence = GEMINI_TEST_SENTENCE

        val apiKey = keyStore.loadKey()
        if (apiKey.isNullOrBlank()) {
            val r = Result(
                engine = "gemini",
                pair = sentence.pair,
                input = sentence.text,
                output = "ERROR: no API key in GeminiKeyStore",
                latencyMs = 0L,
                loadMs = 0L,
                isError = true,
            )
            logResult(r)
            return@withContext r
        }

        val t0 = System.currentTimeMillis()
        val directResult = client.translate(sentence.text, sentence.pair, apiKey)
        val elapsed = System.currentTimeMillis() - t0

        val (output, isError) = when (directResult) {
            is GeminiDirectClient.DirectResult.Ok -> Pair(directResult.text, false)
            GeminiDirectClient.DirectResult.GeoBlocked -> Pair("ERROR: geo-blocked", true)
            GeminiDirectClient.DirectResult.KeyRejected -> Pair("ERROR: key rejected", true)
            is GeminiDirectClient.DirectResult.Failed -> Pair("ERROR: ${directResult.cause}", true)
        }

        val r = Result(
            engine = "gemini",
            pair = sentence.pair,
            input = sentence.text,
            output = output,
            latencyMs = elapsed,
            loadMs = 0L,
            isError = isError,
        )
        logResult(r)
        Log.i(TAG, "=== FlexBench Gemini test complete ===")
        r
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
