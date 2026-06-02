package dev.flextranslate.foundation

import java.io.File

/**
 * Описывает, как streaming-модель sherpa-onnx раскладывается на файлы на устройстве и какое поле
 * [OnlineModelConfig][com.k2fsa.sherpa.onnx.OnlineModelConfig] заполняет.
 *
 * Два вида, потому что две демо-модели используют разные декодеры:
 *  - [ToneCtc]: русская streaming CTC модель T-one — один `model.onnx` + `tokens.txt`.
 *  - [Transducer]: английский streaming zipformer — encoder/decoder/joiner `.onnx` + `tokens.txt`.
 *
 * Имена файлов совпадают с апстрим-раскладкой sherpa-onnx для каждой модели, чтобы обычный
 * `adb push` релизных артефактов (переименованных в эти короткие имена) попадал куда надо.
 */
sealed interface AsrModelSpec {
    /** Стабильный id модели, он же имя подкаталога на устройстве внутри `models/`. */
    val modelId: String

    /** Относительные имена нужных модели файлов — и для загрузки, и для проверки наличия. */
    val requiredFiles: List<String>

    /** Абсолютные пути внутри [modelDir] в порядке [requiredFiles]. */
    fun absolutePaths(modelDir: File): List<File> = requiredFiles.map { File(modelDir, it) }

    /** True только если каждый нужный файл есть и непустой. */
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
 * Связывает реестр [AsrCandidate] с конкретными [AsrModelSpec]. Возвращаются только кандидаты с
 * известной раскладкой sherpa-onnx; всё остальное — без спеки (нет спеки → плейсхолдер).
 */
object AsrModelSpecs {
    /** Основная RU-демо: T-one streaming CTC (Apache-2.0). */
    val RU_T_ONE: AsrModelSpec = AsrModelSpec.ToneCtc(
        modelId = "ru-t-one-streaming-2025-09-08",
    )

    /** EN-демо: streaming zipformer transducer int8 chunk-16-left-128 (Apache-2.0). */
    val EN_ZIPFORMER: AsrModelSpec = AsrModelSpec.Transducer(
        modelId = "en-zipformer-mid-high-2023-06-26",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
    )

    /**
     * EN-демо для слабых устройств: компактный 20M streaming zipformer transducer int8
     * (`sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, Apache-2.0). Раскладка та же, что у
     * [EN_ZIPFORMER]; нужна для low-tier EN кандидата из device-lab, чтобы его пак можно было
     * поставить, а не висел мёртвой неразрешимой строкой.
     */
    val EN_ZIPFORMER_20M: AsrModelSpec = AsrModelSpec.Transducer(
        modelId = "en-zipformer-20m-low-tier-2023-02-17",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
    )

    /**
     * Двуязычная ZH/EN-демо: streaming zipformer transducer int8 (Apache-2.0).
     * Одна модель покрывает оба диалоговых сценария: ZH-собеседник → RU-юзер и EN-собеседник → RU-юзер.
     */
    val ZH_EN_BILINGUAL: AsrModelSpec = AsrModelSpec.Transducer(
        modelId = "zh-en-bilingual-zipformer-2023-02-20",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        modelType = "zipformer",
    )

    /** Все модели с известной раскладкой sherpa-onnx. */
    val all: List<AsrModelSpec> = listOf(RU_T_ONE, EN_ZIPFORMER, EN_ZIPFORMER_20M, ZH_EN_BILINGUAL)

    private val byCandidateId: Map<String, AsrModelSpec> = all.associateBy { it.modelId }

    fun forCandidate(candidate: AsrCandidate): AsrModelSpec? = byCandidateId[candidate.id]

    fun forLanguage(languageCode: String): AsrModelSpec? = when (languageCode.lowercase()) {
        "ru" -> RU_T_ONE
        "en" -> EN_ZIPFORMER
        "zh" -> ZH_EN_BILINGUAL
        else -> null
    }
}
