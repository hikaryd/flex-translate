package dev.flextranslate.foundation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Детерминированный бенч только для debug-сборки. Запускается через intent-extra
 * `-e BENCH 1` (например, `adb shell am start -n dev.flextranslate/.foundation.MainActivity -e BENCH 1`).
 *
 * Для каждой пары движок × предложение меряем:
 *  - время загрузки модели (первый перевод, мс)
 *  - задержку на перевод (мс)
 *  - реальный текст перевода (никогда не выдумываем — если движок недоступен или модели нет,
 *    в результат пишем честную причину провала, а не фейковый текст)
 *
 * Результаты летят в logcat под тегом [TAG] в tab-разделённом формате, удобном для grep:
 *   FlexBench  engine=<id>  pair=<src->tgt>  input=<text>  output=<text>  latency_ms=<N>  load_ms=<N>
 *
 * Тест Gemini BYOK: триггер `-e GEMINI_TEST 1`. Читает ключ из [AndroidGeminiKeyStore],
 * дёргает [GeminiDirectClient] напрямую (без CloudCallGate — это debug-бенч) и логирует реальный
 * перевод и задержку. Ключ нигде не логируется и не сохраняется этим кодом.
 *
 * Код только для debug — в релизе недостижим и не добавляет накладных расходов к работе приложения.
 * Не пишет на диск, не трогает UI-состояние и аудиопайплайн — только напрямую зовёт провайдеров
 * перевода и логирует.
 */
object BenchmarkRunner {

    const val TAG = "FlexBench"
    const val INTENT_EXTRA = "BENCH"
    const val INTENT_EXTRA_GEMINI = "GEMINI_TEST"

    /**
     * Только debug: закидывает Gemini API-ключ в [AndroidGeminiKeyStore] через intent-extra.
     * Триггер `-e SET_GEMINI_KEY <key>`. Значение ключа уходит только в защищённое хранилище и
     * нигде не логируется. Нужно лишь чтобы обойти возню с IME/буфером обмена в тестовой лаборатории.
     */
    const val INTENT_EXTRA_SET_GEMINI_KEY = "SET_GEMINI_KEY"

    /** Фиксированный набор фраз для детерминированного сравнения движков. */
    private val SENTENCES = listOf(
        Sentence("ru->en", "Привет, как дела?"),
        Sentence("ru->en", "Я хочу заказать такси до аэропорта."),
        Sentence("ru->en", "Спасибо за вашу помощь."),
        Sentence("en->ru", "Good morning, what time is breakfast?"),
        Sentence("en->ru", "Please call an ambulance immediately."),
        Sentence("zh->ru", "你好，请问洗手间在哪里？"),
    )

    /** Одна фраза для замера задержки Gemini BYOK. */
    private val GEMINI_TEST_SENTENCE = Sentence("ru->en", "Привет, как дела?")

    data class Sentence(val pair: String, val text: String)

    data class Result(
        val engine: String,
        val pair: String,
        val input: String,
        val output: String,          // реальный вывод модели либо честное описание провала
        val latencyMs: Long,
        val loadMs: Long,            // 0 для всех вызовов после загрузки модели
        val isError: Boolean,        // true, когда в output причина провала, а не перевод
    )

    /**
     * Гоняет бенч по всем доступным движкам и возвращает список [Result].
     * Вызывать только из корутины (уходит на [Dispatchers.IO]).
     * Каждый результат пишет в logcat под [TAG].
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
     * Только debug: закидывает Gemini API-ключ в [AndroidGeminiKeyStore].
     * Триггер `-e SET_GEMINI_KEY <key>`. Ключ сохраняется в шифрованное хранилище и нигде не
     * логируется. В лог пишем только факт успеха/провала, без самого значения ключа.
     */
    fun provisionGeminiKey(context: Context, apiKey: String) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "SET_GEMINI_KEY: key is blank — skipped")
            return
        }
        val keyStore = AndroidGeminiKeyStore(context)
        keyStore.saveKey(apiKey)
        // Логируем только подтверждение — само значение ключа никогда.
        Log.i(TAG, "SET_GEMINI_KEY: key provisioned to GeminiKeyStore (${apiKey.length} chars)")
    }

    /**
     * Тест прямого вызова Gemini BYOK. Триггер `-e GEMINI_TEST 1`.
     *
     * Читает API-ключ из [AndroidGeminiKeyStore] (EncryptedSharedPreferences).
     * Зовёт [GeminiDirectClient] напрямую — это debug-бенч, проверки согласия [CloudCallGate]
     * тут намеренно пропущены (ключ положили явно ради этого теста).
     *
     * Безопасность: ключ достаём в последний момент и отдаём только в [GeminiDirectClient.translate].
     * Никогда не логируем, никогда не включаем ни в одно лог-сообщение и не пишем в файлы.
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
            val direction = MtDirection.parse(sentence.pair) ?: continue  // неподдерживаемые пары пропускаем
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
