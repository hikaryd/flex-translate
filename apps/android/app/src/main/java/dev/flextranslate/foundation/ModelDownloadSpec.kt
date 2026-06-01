package dev.flextranslate.foundation

/**
 * One downloadable file of a model pack: the on-device file name the stores expect (matching the
 * spec's `requiredFiles`), the source URL to fetch it from (Hugging Face `resolve/main`), the
 * expected SHA-256 for integrity verification, and the expected size in bytes for progress maths.
 *
 * The values mirror the host-measured `package-evidence.json` under
 * `benchmarks/device-lab/model-artifacts/<id>/` — same files a dev `adb push` would land, now
 * acquired by a real in-app download instead.
 */
data class ModelFileDownload(
    val fileName: String,
    val sourceUrl: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * A full downloadable model pack: its [modelId] (the on-device sub-directory under `models/`) and
 * the ordered list of files that make it whole. [totalBytes] is the sum used for the "size before
 * download" label and the aggregate progress denominator.
 */
data class ModelDownloadSpec(
    val modelId: String,
    val files: List<ModelFileDownload>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val totalMb: Double get() = totalBytes.toDouble() / BYTES_PER_MB

    private companion object {
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}

/**
 * Registry of every downloadable pack, keyed by the same [modelId] the [AsrModelStore] and
 * [MtModelStore] resolve. File names are the SHORT on-device names the runtime loads (e.g.
 * `encoder.int8.onnx`), while URLs/sha256/sizes come from the upstream artifacts — so the download
 * manager writes each file under its expected name and verifies it against the host-measured hash.
 *
 * This is the ONLY acquisition path now: weights are not bundled in the APK, and the dev `adb push`
 * was only a shortcut. Sizes/hashes are sourced from
 * `benchmarks/device-lab/model-artifacts/<id>/package-evidence.json`.
 */
object ModelDownloadSpecs {

    private const val HF = "https://huggingface.co"

    /** RU primary: T-one streaming CTC (Apache-2.0) — model.onnx + tokens.txt. */
    private val RU_T_ONE = ModelDownloadSpec(
        modelId = AsrModelSpecs.RU_T_ONE.modelId,
        files = listOf(
            ModelFileDownload(
                fileName = "model.onnx",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08/resolve/main/model.onnx",
                sha256 = "5ded080e2a6c86ecc11bcb0902d77524eb3e8b0844cb0c0754347f5aafb4dabc",
                sizeBytes = 144_193_702L,
            ),
            ModelFileDownload(
                fileName = "tokens.txt",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08/resolve/main/tokens.txt",
                sha256 = "27f7b3ba2096c572375fba1a6b29af1f80d86e08a329940612908112695f97e0",
                sizeBytes = 202L,
            ),
        ),
    )

    /**
     * EN mid/high: streaming zipformer transducer int8 (Apache-2.0). Upstream uses the long
     * `*-epoch-99-avg-1-chunk-16-left-128.int8.onnx` names; on-device the runtime loads the short
     * `encoder.int8.onnx` / `decoder.int8.onnx` / `joiner.int8.onnx`, so we download-then-rename.
     */
    private val EN_ZIPFORMER = ModelDownloadSpec(
        modelId = AsrModelSpecs.EN_ZIPFORMER.modelId,
        files = listOf(
            ModelFileDownload(
                fileName = "encoder.int8.onnx",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                sha256 = "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1",
                sizeBytes = 71_083_163L,
            ),
            ModelFileDownload(
                fileName = "decoder.int8.onnx",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                sha256 = "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02",
                sizeBytes = 1_307_236L,
            ),
            ModelFileDownload(
                fileName = "joiner.int8.onnx",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                sha256 = "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297",
                sizeBytes = 259_335L,
            ),
            ModelFileDownload(
                fileName = "tokens.txt",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/tokens.txt",
                sha256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
                sizeBytes = 5_048L,
            ),
        ),
    )

    /** ZH/EN bilingual: streaming zipformer transducer int8 (Apache-2.0). Long upstream → short. */
    private val ZH_EN_BILINGUAL = ModelDownloadSpec(
        modelId = AsrModelSpecs.ZH_EN_BILINGUAL.modelId,
        files = listOf(
            ModelFileDownload(
                fileName = "encoder.int8.onnx",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.int8.onnx",
                sha256 = "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
                sizeBytes = 181_895_032L,
            ),
            ModelFileDownload(
                fileName = "decoder.int8.onnx",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/decoder-epoch-99-avg-1.int8.onnx",
                sha256 = "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa",
                sizeBytes = 13_091_040L,
            ),
            ModelFileDownload(
                fileName = "joiner.int8.onnx",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/joiner-epoch-99-avg-1.int8.onnx",
                sha256 = "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0",
                sizeBytes = 3_228_404L,
            ),
            ModelFileDownload(
                fileName = "tokens.txt",
                sourceUrl = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/tokens.txt",
                sha256 = "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
                sizeBytes = 56_317L,
            ),
        ),
    )

    /**
     * M2M-100 418M ONNX (MIT) — the balanced on-device MT pack. On-device names mirror the
     * [MtModelSpecs.M2M100_418M] split-decoder layout; the upstream files share the same names
     * under `Xenova/m2m100_418M/resolve/main/onnx/`.
     */
    private val M2M100_418M = ModelDownloadSpec(
        modelId = MtModelSpecs.M2M100_418M.modelId,
        files = listOf(
            ModelFileDownload(
                fileName = "encoder_model_quantized.onnx",
                sourceUrl = "$HF/Xenova/m2m100_418M/resolve/main/onnx/encoder_model_quantized.onnx",
                sha256 = "13a94e354a9140764eb81102d77d3ec6952d796e6f113c651eeb3c3443da0386",
                sizeBytes = 287_856_370L,
            ),
            ModelFileDownload(
                fileName = "decoder_model_quantized.onnx",
                sourceUrl = "$HF/Xenova/m2m100_418M/resolve/main/onnx/decoder_model_quantized.onnx",
                sha256 = "6015e31c8976659aedb06058c4dadf0f400d087a3f9830f838e68f220d79bcb6",
                sizeBytes = 339_181_945L,
            ),
            ModelFileDownload(
                fileName = "decoder_with_past_model_quantized.onnx",
                sourceUrl = "$HF/Xenova/m2m100_418M/resolve/main/onnx/decoder_with_past_model_quantized.onnx",
                sha256 = "780982a10d1a966978d74c210558a1d733959625c718ca7f72ddfd4ba56a23a5",
                sizeBytes = 313_662_487L,
            ),
            ModelFileDownload(
                fileName = "tokenizer.json",
                sourceUrl = "$HF/Xenova/m2m100_418M/resolve/main/tokenizer.json",
                sha256 = "03d9e111731c2d71f39a2c2a88499743e4c251385d07f0384b4349a23ba54363",
                sizeBytes = 7_988_527L,
            ),
        ),
    )

    /**
     * MiLMMT-46-4B Q6_K GGUF (Gemma license) — the quality MT pack. Single ~3.74 GB file. The
     * llama.cpp runtime is still deferred (graceful "runtime not installed" gating), but the file
     * is downloadable like any other pack — downloading is independent of runtime availability.
     */
    private val MILMMT_46_4B_Q6 = ModelDownloadSpec(
        modelId = MtModelSpecs.MILMMT_46_4B_Q6.modelId,
        files = listOf(
            ModelFileDownload(
                fileName = "MiLMMT-46-4B-v0.1.Q6_K.gguf",
                sourceUrl = "$HF/mradermacher/MiLMMT-46-4B-v0.1-GGUF/resolve/main/MiLMMT-46-4B-v0.1.Q6_K.gguf",
                sha256 = "2779a25cb1d55acfa4963af70e187fd022101ebb533006197cb12dc723d862cf",
                sizeBytes = 3_741_376_000L,
            ),
        ),
    )

    val all: List<ModelDownloadSpec> =
        listOf(RU_T_ONE, EN_ZIPFORMER, ZH_EN_BILINGUAL, M2M100_418M, MILMMT_46_4B_Q6)

    private val byModelId: Map<String, ModelDownloadSpec> = all.associateBy { it.modelId }

    fun forModelId(modelId: String): ModelDownloadSpec? = byModelId[modelId]
}
