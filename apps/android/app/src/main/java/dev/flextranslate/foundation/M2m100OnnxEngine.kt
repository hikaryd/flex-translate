package dev.flextranslate.foundation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Жадный авторегрессивный перевод M2M-100 поверх Microsoft ONNX Runtime ([OrtSession]).
 *
 * Только реальный вывод модели — каждая возвращённая строка получена прогоном энкодера один раз и
 * пошаговым декодером с KV-cache до EOS. Ничего не выдумывается; при любом сбое движок отдаёт ошибку
 * вызывающему, и тот честно гейтит.
 *
 * Берём РАЗДЕЛЁННУЮ пару decoder'ов (не merged): в merged-графе ветка `If`/no-cache решейпит
 * cross-attention KV энкодера так, что нативный ORT-mobile падает на пустом prefill (проверено на
 * устройстве). Разделённая пара обходит узел `If`:
 *  - encoder: `input_ids[1,S]`, `attention_mask[1,S]` (int64) → `last_hidden_state[1,S,1024]`
 *  - prefill decoder: `input_ids[1,1]`, `encoder_attention_mask[1,S]`, `encoder_hidden_states`
 *    → `logits[1,1,V]` + полный KV-cache `present.*.{decoder,encoder}.*`.
 *  - with-past decoder: `input_ids[1,1]`, `encoder_attention_mask[1,S]`, все `past_key_values.*`
 *    → `logits[1,1,V]` + новые `present.*.decoder.*` (encoder KV статичен, переносится дальше).
 *
 * Шаг 0 — prefill decoder с decoder-start токеном (id EOS); первый сгенерированный токен ФОРСИРУЕМ
 * в id целевого языка. Дальше идёт with-past decoder, наращивая self-attn KV декодера и переиспользуя
 * статичный cross-attn KV энкодера из prefill.
 *
 * Не потокобезопасен; создавать/использовать/закрывать на одном рабочем потоке.
 */
class M2m100OnnxEngine private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoderPrefill: OrtSession,
    private val decoderWithPast: OrtSession,
    private val tokenizer: M2m100Tokenizer,
) {

    /** Переводит [text] с [sourceLang] на [targetLang] (например, "en" → "ru"). */
    fun translate(text: String, sourceLang: String, targetLang: String, maxNewTokens: Int = DEFAULT_MAX_TOKENS): String {
        val sourceIds = tokenizer.encodeSource(text, sourceLang)
        val encoderHidden = runEncoder(sourceIds)
        try {
            return decodeGreedy(sourceIds, encoderHidden, targetLang, maxNewTokens)
        } finally {
            encoderHidden.close()
        }
    }

    private fun runEncoder(sourceIds: IntArray): OnnxTensor {
        val seq = sourceIds.size
        val inputIds = longTensor(sourceIds.map { it.toLong() }.toLongArray(), longArrayOf(1, seq.toLong()))
        val attentionMask = longTensor(LongArray(seq) { 1L }, longArrayOf(1, seq.toLong()))
        return inputIds.use { ids ->
            attentionMask.use { mask ->
                encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { outputs ->
                    // Копируем last_hidden_state в собственный тензор, который живёт весь цикл
                    // декодирования. Обязательно до закрытия `outputs` (и его дочерних тензоров).
                    copyTensor(outputs[ENCODER_OUTPUT].get() as OnnxTensor)
                }
            }
        }
    }

    private fun decodeGreedy(
        sourceIds: IntArray,
        encoderHidden: OnnxTensor,
        targetLang: String,
        maxNewTokens: Int,
    ): String {
        val seq = sourceIds.size
        val targetLangId = tokenizer.targetLangId(targetLang)
        val generated = ArrayList<Int>(maxNewTokens)

        // Prefill (шаг 0): сеем decoder-start токеном и забираем полный KV-cache.
        var pastKv = runPrefill(seq, encoderHidden)
        var nextInput = targetLangId // forced-BOS: первый *сгенерированный* токен — целевой язык.

        try {
            for (step in 1 until maxNewTokens) {
                val (nextToken, newPast) = runWithPast(seq, nextInput, pastKv)
                closeAll(pastKv)
                pastKv = newPast
                if (nextToken == tokenizer.eosId || nextToken == tokenizer.padId) break
                generated += nextToken
                nextInput = nextToken
            }
        } finally {
            closeAll(pastKv)
        }

        Log.i(TAG, "decoded ${generated.size} tokens for $targetLang")
        return tokenizer.decode(generated.toIntArray())
    }

    /** Шаг 0: прогон prefill decoder; возвращает полный собственный KV-cache (decoder + encoder). */
    private fun runPrefill(seq: Int, encoderHidden: OnnxTensor): MutableMap<String, OnnxTensor> {
        val decInput = longTensor(longArrayOf(tokenizer.decoderStartId.toLong()), longArrayOf(1, 1))
        val encoderMask = longTensor(LongArray(seq) { 1L }, longArrayOf(1, seq.toLong()))
        return decInput.use { ids ->
            encoderMask.use { mask ->
                val inputs = mapOf(
                    "input_ids" to ids,
                    "encoder_attention_mask" to mask,
                    "encoder_hidden_states" to encoderHidden,
                )
                decoderPrefill.run(inputs).use { result ->
                    extractPresent(result, includeEncoder = true)
                }
            }
        }
    }

    /**
     * Шаги 1+: прогон with-past decoder на один токен. Возвращает argmax-токен и следующий
     * KV-cache (новый decoder KV из выходов + статичный encoder KV, перенесённый из [pastKv]).
     */
    private fun runWithPast(
        seq: Int,
        inputToken: Int,
        pastKv: Map<String, OnnxTensor>,
    ): Pair<Int, MutableMap<String, OnnxTensor>> {
        val decInput = longTensor(longArrayOf(inputToken.toLong()), longArrayOf(1, 1))
        val encoderMask = longTensor(LongArray(seq) { 1L }, longArrayOf(1, seq.toLong()))
        return decInput.use { ids ->
            encoderMask.use { mask ->
                val inputs = HashMap<String, OnnxTensor>()
                inputs["input_ids"] = ids
                inputs["encoder_attention_mask"] = mask
                inputs.putAll(pastKv)
                decoderWithPast.run(inputs).use { result ->
                    val token = argmaxLastStep(result[DECODER_LOGITS].get() as OnnxTensor)
                    // Новый self-attn KV декодера из выходов этого шага.
                    val newPast = extractPresent(result, includeEncoder = false)
                    // Переносим статичный cross-attn KV энкодера (копируем, чтобы владеть им).
                    for (layer in 0 until NUM_LAYERS) {
                        for (part in ENCODER_PARTS) {
                            val name = "past_key_values.$layer.$part"
                            pastKv[name]?.let { newPast[name] = copyTensor(it) }
                        }
                    }
                    token to newPast
                }
            }
        }
    }

    /** argmax по измерению словаря на последнем шаге декодера. */
    private fun argmaxLastStep(logits: OnnxTensor): Int {
        val shape = logits.info.shape // [1, T, V]
        val vocab = shape[2].toInt()
        val buffer = logits.floatBuffer ?: error("logits not float")
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        val base = buffer.remaining() - vocab // последний шаг по времени
        for (v in 0 until vocab) {
            val value = buffer.get(base + v)
            if (value > bestVal) {
                bestVal = value
                bestIdx = v
            }
        }
        return bestIdx
    }

    /**
     * Копирует выходы decoder `present.*` в собственные тензоры `past_key_values.*`. Prefill отдаёт
     * и cross-attn KV энкодера ([includeEncoder] = true); with-past заново выдаёт только decoder KV.
     */
    private fun extractPresent(result: OrtSession.Result, includeEncoder: Boolean): MutableMap<String, OnnxTensor> {
        val out = HashMap<String, OnnxTensor>()
        val parts = if (includeEncoder) ALL_PARTS else DECODER_PARTS
        for (layer in 0 until NUM_LAYERS) {
            for (part in parts) {
                val tensor = result["present.$layer.$part"].get() as OnnxTensor
                out["past_key_values.$layer.$part"] = copyTensor(tensor)
            }
        }
        return out
    }

    /** Глубокая копия float-тензора в собственный OnnxTensor (переживает Result.close()). */
    private fun copyTensor(src: OnnxTensor): OnnxTensor {
        val shape = src.info.shape
        val floats = src.floatBuffer ?: error("tensor not float")
        val copy = FloatArray(floats.remaining())
        floats.get(copy)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(copy), shape)
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun closeAll(tensors: Map<String, OnnxTensor>) {
        tensors.values.forEach { runCatching { it.close() } }
    }

    fun close() {
        runCatching { encoder.close() }
        runCatching { decoderPrefill.close() }
        runCatching { decoderWithPast.close() }
    }

    companion object {
        private const val TAG = "M2m100OnnxEngine"
        private const val ENCODER_OUTPUT = "last_hidden_state"
        private const val DECODER_LOGITS = "logits"
        private const val DEFAULT_MAX_TOKENS = 96

        // Архитектура M2M-100 418M: 12 слоёв декодера (heads/dim неявно зашиты в тензорах графа).
        private const val NUM_LAYERS = 12
        private val DECODER_PARTS = listOf("decoder.key", "decoder.value")
        private val ENCODER_PARTS = listOf("encoder.key", "encoder.value")
        private val ALL_PARTS = DECODER_PARTS + ENCODER_PARTS

        /**
         * Собирает движок из файлов модели на устройстве. Возвращает null (никогда не бросает),
         * если файлов нет или сессия/токенизатор не инициализировались — вызывающий честно гейтит.
         */
        fun create(spec: MtModelSpec.Seq2SeqOnnx, modelDir: File): M2m100OnnxEngine? = runCatching {
            val encoderFile = File(modelDir, spec.encoder)
            val prefillFile = File(modelDir, spec.decoderPrefill)
            val withPastFile = File(modelDir, spec.decoderWithPast)
            val tokenizerFile = File(modelDir, spec.tokenizer)
            if (!encoderFile.isFile || !prefillFile.isFile || !withPastFile.isFile || !tokenizerFile.isFile) {
                return null
            }
            val tokenizer = M2m100Tokenizer.load(tokenizerFile) ?: return null

            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(DEFAULT_THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val encoder = env.createSession(encoderFile.absolutePath, options)
            val prefill = env.createSession(prefillFile.absolutePath, options)
            val withPast = env.createSession(withPastFile.absolutePath, options)
            Log.i(TAG, "M2M-100 OrtSession ready: enc=${spec.encoder} prefill=${spec.decoderPrefill} withPast=${spec.decoderWithPast}")
            M2m100OnnxEngine(env, encoder, prefill, withPast, tokenizer)
        }.getOrElse { t ->
            Log.e(TAG, "engine create failed", t)
            null
        }

        private const val DEFAULT_THREADS = 2
    }
}
