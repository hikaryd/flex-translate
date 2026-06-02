package dev.flextranslate.foundation

import java.io.File

/**
 * Описывает, как on-device модель перевода ложится на свои файлы в `models/`.
 *
 * Флагман — [M2M100], ONNX-экспорт `facebook/m2m100_418M` (`Xenova/m2m100_418M`, MIT). ОДНА модель
 * тянет все четыре демо-направления (RU↔EN, RU↔ZH): генерацию подталкиваем форсированным токеном
 * целевого языка — без английского пивота.
 *
 * Раскладка (имена файлов на устройстве, куда обычный `adb push` кладёт в `models/<modelId>/`):
 *  - `encoder_model_quantized.onnx`            — граф encoder (input_ids, attention_mask → hidden)
 *  - `decoder_model_merged_quantized.onnx`     — merged decoder с KV-cache (жадная авторегрессия)
 *  - `tokenizer.json`                          — спека HF fast-tokenizer (BPE + metaspace + языки)
 *
 * Веса в APK не зашиты (лицензия мягкая, но файлы ~600 МБ) — приезжают через загрузку при первом
 * запуске (или `adb push` для демо на устройстве), ровно как ASR-паки.
 */
sealed interface MtModelSpec {
    /** Стабильный id модели, он же имя подкаталога в `models/` на устройстве. */
    val modelId: String

    /** Относительные имена нужных файлов — используются и для загрузки, и для проверки наличия. */
    val requiredFiles: List<String>

    /** Абсолютные пути внутри [modelDir], в порядке [requiredFiles]. */
    fun absolutePaths(modelDir: File): List<File> = requiredFiles.map { File(modelDir, it) }

    /** True, только если все нужные файлы есть и непустые. */
    fun isInstalled(modelDir: File): Boolean =
        absolutePaths(modelDir).all { it.isFile && it.length() > 0L }

    /**
     * Seq2seq encoder/decoder ONNX-модель с tokenizer.json от HuggingFace. Берём РАЗДЕЛЁННУЮ пару
     * decoder'ов (не "merged" из Transformers.js): в merged-графе ветка `If`/no-cache решейпит
     * cross-attention KV энкодера так, что нативная мобильная сборка ONNX Runtime падает при пустом
     * prefill-past (проверено на устройстве: ORT_RUNTIME_EXCEPTION в `encoder_attn/Reshape_4`).
     * Разделённая пара обходит узел `If` целиком:
     *  - [decoderPrefill]: входы (input_ids, encoder_attention_mask, encoder_hidden_states) →
     *    logits + полный KV-cache `present.*` (decoder + encoder).
     *  - [decoderWithPast]: входы (input_ids, encoder_attention_mask, past_key_values.*) →
     *    logits + новые `present.*.decoder.*` (encoder KV статичен и переносится дальше).
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
     * Однофайловая GGUF-модель, которую крутим через вендоренный llama.cpp ([LlamaCppBridge]). GGUF
     * несёт свой tokenizer + chat-метаданные, так что квантованной decoder-only LLM (MiLMMT-46-4B,
     * архитектура Gemma-3) нужен ровно ОДИН файл на устройстве. Как и ONNX-паки, в APK не зашит
     * (~3.74 ГБ) — приезжает загрузкой при первом запуске (или `adb push` для демо на устройстве).
     */
    data class Gguf(
        override val modelId: String,
        val gguf: String,
    ) : MtModelSpec {
        override val requiredFiles: List<String> = listOf(gguf)
    }
}

/** Конкретные спеки on-device MT-моделей. */
object MtModelSpecs {
    /**
     * M2M-100 418M, ONNX (quantized) экспорт из `Xenova/m2m100_418M` (MIT). Прямой RU↔EN и RU↔ZH
     * через forced-BOS токен целевого языка — флагманская on-device MT-модель WS4.
     */
    val M2M100_418M: MtModelSpec.Seq2SeqOnnx = MtModelSpec.Seq2SeqOnnx(
        modelId = "m2m100-418m",
        encoder = "encoder_model_quantized.onnx",
        decoderPrefill = "decoder_model_quantized.onnx",
        decoderWithPast = "decoder_with_past_model_quantized.onnx",
    )

    /**
     * MiLMMT-46-4B v0.1, community Q6_K GGUF (`mradermacher/MiLMMT-46-4B-v0.1-GGUF`). Файнтюн
     * Gemma-3-4B под many-to-many MT (46 языков, включая RU/EN/ZH) — КАЧЕСТВЕННЫЙ on-device тир WS4,
     * крутится через llama.cpp. ОДИН файл ~3.74 ГБ; базовая лицензия `gemma` (прокидывается пользователю).
     */
    val MILMMT_46_4B_Q6: MtModelSpec.Gguf = MtModelSpec.Gguf(
        modelId = "milmmt-46-4b-q6",
        gguf = "MiLMMT-46-4B-v0.1.Q6_K.gguf",
    )

    val all: List<MtModelSpec> = listOf(M2M100_418M, MILMMT_46_4B_Q6)

    private val byModelId: Map<String, MtModelSpec> = all.associateBy { it.modelId }

    fun forModelId(modelId: String): MtModelSpec? = byModelId[modelId]
}
