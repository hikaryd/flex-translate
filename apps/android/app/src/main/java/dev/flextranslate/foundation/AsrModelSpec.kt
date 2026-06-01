package dev.flextranslate.foundation

import java.io.File

/**
 * Describes how a sherpa-onnx streaming model maps onto on-device files and which
 * [OnlineModelConfig][com.k2fsa.sherpa.onnx.OnlineModelConfig] field it populates.
 *
 * Two kinds are modelled because the two demo models use different decoders:
 *  - [ToneCtc]: the T-one Russian streaming CTC model — a single `model.onnx` + `tokens.txt`.
 *  - [Transducer]: the English streaming zipformer — encoder/decoder/joiner `.onnx` + `tokens.txt`.
 *
 * File names match the sherpa-onnx upstream layout for each model so a plain `adb push` of the
 * release artifacts (renamed to these short names) lands in the right place.
 */
sealed interface AsrModelSpec {
    /** Stable model id, also the on-device sub-directory name under `models/`. */
    val modelId: String

    /** Relative file names this model needs, used both for download and presence checks. */
    val requiredFiles: List<String>

    /** Absolute file paths inside [modelDir], in [requiredFiles] order. */
    fun absolutePaths(modelDir: File): List<File> = requiredFiles.map { File(modelDir, it) }

    /** True only when every required file exists and is non-empty. */
    fun isInstalled(modelDir: File): Boolean =
        absolutePaths(modelDir).all { it.isFile && it.length() > 0L }

    data class ToneCtc(
        override val modelId: String,
        val model: String = "model.onnx",
        val tokens: String = "tokens.txt",
    ) : AsrModelSpec {
        override val requiredFiles: List<String> = listOf(model, tokens)
    }

    data class Transducer(
        override val modelId: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String = "tokens.txt",
        val modelType: String = "zipformer2",
    ) : AsrModelSpec {
        override val requiredFiles: List<String> = listOf(encoder, decoder, joiner, tokens)
    }
}

/**
 * Maps the [AsrCandidate] registry onto concrete [AsrModelSpec]s. Only candidates with a known
 * sherpa-onnx layout are returned; everything else is left ungated (no spec → placeholder).
 */
object AsrModelSpecs {
    /** RU primary demo: T-one streaming CTC (Apache-2.0). */
    val RU_T_ONE: AsrModelSpec = AsrModelSpec.ToneCtc(
        modelId = "ru-t-one-streaming-2025-09-08",
    )

    /** EN demo: streaming zipformer transducer int8 chunk-16-left-128 (Apache-2.0). */
    val EN_ZIPFORMER: AsrModelSpec = AsrModelSpec.Transducer(
        modelId = "en-zipformer-mid-high-2023-06-26",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
    )

    /**
     * ZH/EN bilingual demo: streaming zipformer transducer int8 (Apache-2.0).
     * One model covers both ZH speaker → RU user and EN speaker → RU user dialogue flows.
     */
    val ZH_EN_BILINGUAL: AsrModelSpec = AsrModelSpec.Transducer(
        modelId = "zh-en-bilingual-zipformer-2023-02-20",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        modelType = "zipformer",
    )

    /** Every model id with a known sherpa-onnx layout. */
    val all: List<AsrModelSpec> = listOf(RU_T_ONE, EN_ZIPFORMER, ZH_EN_BILINGUAL)

    private val byCandidateId: Map<String, AsrModelSpec> = all.associateBy { it.modelId }

    fun forCandidate(candidate: AsrCandidate): AsrModelSpec? = byCandidateId[candidate.id]

    fun forLanguage(languageCode: String): AsrModelSpec? = when (languageCode.lowercase()) {
        "ru" -> RU_T_ONE
        "en" -> EN_ZIPFORMER
        "zh" -> ZH_EN_BILINGUAL
        else -> null
    }
}
