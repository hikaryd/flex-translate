package dev.flextranslate.foundation

import java.io.File

/**
 * Describes how an on-device machine-translation model maps onto its files under `models/`.
 *
 * The flagship spec is [M2M100], the ONNX export of `facebook/m2m100_418M`
 * (`Xenova/m2m100_418M`, MIT). ONE model serves all four demo directions (RU↔EN, RU↔ZH) by
 * conditioning generation on a forced target-language token — no English pivot.
 *
 * Layout (the on-device file names a plain `adb push` lands in `models/<modelId>/`):
 *  - `encoder_model_quantized.onnx`            — encoder graph (input_ids, attention_mask → hidden)
 *  - `decoder_model_merged_quantized.onnx`     — merged KV-cache decoder (greedy autoregression)
 *  - `tokenizer.json`                          — HF fast-tokenizer spec (BPE + metaspace + langs)
 *
 * Weights are NOT bundled in the APK (license is permissive but the files are ~600 MB); they
 * arrive via first-run download (or `adb push` for the device demo), exactly like the ASR packs.
 */
sealed interface MtModelSpec {
    /** Stable model id, also the on-device sub-directory name under `models/`. */
    val modelId: String

    /** Relative file names this model needs, used for both download and presence checks. */
    val requiredFiles: List<String>

    /** Absolute file paths inside [modelDir], in [requiredFiles] order. */
    fun absolutePaths(modelDir: File): List<File> = requiredFiles.map { File(modelDir, it) }

    /** True only when every required file exists and is non-empty. */
    fun isInstalled(modelDir: File): Boolean =
        absolutePaths(modelDir).all { it.isFile && it.length() > 0L }

    /**
     * Seq2seq encoder/decoder ONNX model with a HuggingFace tokenizer.json. We use the SPLIT
     * decoder pair (not the Transformers.js "merged" decoder): the merged graph's `If`/no-cache
     * branch reshapes the encoder cross-attention KV in a way the native ONNX Runtime mobile build
     * rejects when the prefill past is zero-length (verified on device: ORT_RUNTIME_EXCEPTION at
     * `encoder_attn/Reshape_4`). The split pair avoids the `If` node entirely:
     *  - [decoderPrefill]: inputs (input_ids, encoder_attention_mask, encoder_hidden_states) →
     *    logits + the full `present.*` KV cache (decoder + encoder).
     *  - [decoderWithPast]: inputs (input_ids, encoder_attention_mask, past_key_values.*) →
     *    logits + new `present.*.decoder.*` (encoder KV is static and carried forward).
     */
    data class Seq2SeqOnnx(
        override val modelId: String,
        val encoder: String,
        val decoderPrefill: String,
        val decoderWithPast: String,
        val tokenizer: String = "tokenizer.json",
    ) : MtModelSpec {
        override val requiredFiles: List<String> =
            listOf(encoder, decoderPrefill, decoderWithPast, tokenizer)
    }

    /**
     * Single-file GGUF model run via the vendored llama.cpp ([LlamaCppBridge]). The GGUF carries
     * its own tokenizer + chat metadata, so a quantized decoder-only LLM (MiLMMT-46-4B, Gemma-3
     * architecture) needs exactly ONE on-device file. Like the ONNX packs, it is NOT bundled in the
     * APK (~3.74 GB) — it arrives via first-run download (or `adb push` for the device demo).
     */
    data class Gguf(
        override val modelId: String,
        val gguf: String,
    ) : MtModelSpec {
        override val requiredFiles: List<String> = listOf(gguf)
    }
}

/** Concrete on-device MT model specs. */
object MtModelSpecs {
    /**
     * M2M-100 418M, ONNX (quantized) export from `Xenova/m2m100_418M` (MIT). Direct RU↔EN and
     * RU↔ZH via forced-BOS target-language token — the WS4 flagship on-device MT model.
     */
    val M2M100_418M: MtModelSpec.Seq2SeqOnnx = MtModelSpec.Seq2SeqOnnx(
        modelId = "m2m100-418m",
        encoder = "encoder_model_quantized.onnx",
        decoderPrefill = "decoder_model_quantized.onnx",
        decoderWithPast = "decoder_with_past_model_quantized.onnx",
    )

    /**
     * MiLMMT-46-4B v0.1, community Q6_K GGUF (`mradermacher/MiLMMT-46-4B-v0.1-GGUF`). A Gemma-3-4B
     * fine-tune for many-to-many MT (46 langs incl. RU/EN/ZH) — the WS4 QUALITY on-device tier,
     * run via llama.cpp. ONE ~3.74 GB file; base license `gemma` (passed through to the user).
     */
    val MILMMT_46_4B_Q6: MtModelSpec.Gguf = MtModelSpec.Gguf(
        modelId = "milmmt-46-4b-q6",
        gguf = "MiLMMT-46-4B-v0.1.Q6_K.gguf",
    )

    val all: List<MtModelSpec> = listOf(M2M100_418M, MILMMT_46_4B_Q6)

    private val byModelId: Map<String, MtModelSpec> = all.associateBy { it.modelId }

    fun forModelId(modelId: String): MtModelSpec? = byModelId[modelId]
}
