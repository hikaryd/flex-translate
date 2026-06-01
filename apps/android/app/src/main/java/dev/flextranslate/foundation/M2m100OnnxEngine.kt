package dev.flextranslate.foundation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Greedy autoregressive M2M-100 translation over the Microsoft ONNX Runtime ([OrtSession]).
 *
 * Real model output only — every returned string is produced by running the encoder once and the
 * KV-cache decoder step-by-step until EOS. Nothing is fabricated; on any failure the engine
 * surfaces the error to the caller, which gates honestly.
 *
 * Uses the SPLIT decoder pair (not the merged decoder): the merged graph's `If`/no-cache branch
 * reshapes the encoder cross-attention KV in a way native ORT-mobile rejects at zero-length prefill
 * (verified on device). The split pair sidesteps the `If` node:
 *  - encoder: `input_ids[1,S]`, `attention_mask[1,S]` (int64) → `last_hidden_state[1,S,1024]`
 *  - prefill decoder: `input_ids[1,1]`, `encoder_attention_mask[1,S]`, `encoder_hidden_states`
 *    → `logits[1,1,V]` + full `present.*.{decoder,encoder}.*` KV cache.
 *  - with-past decoder: `input_ids[1,1]`, `encoder_attention_mask[1,S]`, all `past_key_values.*`
 *    → `logits[1,1,V]` + new `present.*.decoder.*` (encoder KV is static, carried forward).
 *
 * Step 0 runs the prefill decoder seeded with the decoder-start token (EOS id); the first generated
 * token is FORCED to the target-language id. Subsequent steps run the with-past decoder, growing the
 * decoder self-attn KV while reusing the static encoder cross-attn KV from prefill.
 *
 * Not thread-safe; create/use/close on one worker thread.
 */
class M2m100OnnxEngine private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoderPrefill: OrtSession,
    private val decoderWithPast: OrtSession,
    private val tokenizer: M2m100Tokenizer,
) {

    /** Translate [text] from [sourceLang] into [targetLang] (e.g. "en" → "ru"). */
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
                    // Copy last_hidden_state into a standalone tensor we own for the whole decode
                    // loop. This MUST happen before `outputs` (and its child tensors) is closed.
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

        // Prefill (step 0): seed with the decoder-start token; harvest the full KV cache.
        var pastKv = runPrefill(seq, encoderHidden)
        var nextInput = targetLangId // forced-BOS: the first *generated* token is the target lang.

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

    /** Step 0: run the prefill decoder; returns the full owned KV cache (decoder + encoder). */
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
     * Steps 1+: run the with-past decoder for one token. Returns the argmax token and the next
     * KV cache (new decoder KV from outputs + the static encoder KV carried forward from [pastKv]).
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
                    // New decoder self-attn KV from this step's outputs.
                    val newPast = extractPresent(result, includeEncoder = false)
                    // Carry the static encoder cross-attn KV forward (copied so we own it).
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

    /** argmax over the vocab dimension of the last decoder step. */
    private fun argmaxLastStep(logits: OnnxTensor): Int {
        val shape = logits.info.shape // [1, T, V]
        val vocab = shape[2].toInt()
        val buffer = logits.floatBuffer ?: error("logits not float")
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        val base = buffer.remaining() - vocab // last time step
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
     * Copy decoder `present.*` outputs into owned `past_key_values.*` tensors. Prefill includes the
     * encoder cross-attn KV ([includeEncoder] = true); with-past only re-emits decoder KV.
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

    /** Deep-copy a float tensor into a standalone OnnxTensor we own (survives Result.close()). */
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

        // M2M-100 418M architecture: 12 decoder layers (heads/dim implicit in the graph tensors).
        private const val NUM_LAYERS = 12
        private val DECODER_PARTS = listOf("decoder.key", "decoder.value")
        private val ENCODER_PARTS = listOf("encoder.key", "encoder.value")
        private val ALL_PARTS = DECODER_PARTS + ENCODER_PARTS

        /**
         * Build an engine from on-device model files. Returns null (never throws) if files are
         * missing or a session/tokenizer fails to initialize — the caller gates honestly.
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
