package dev.flextranslate.foundation

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Точный порт fast-токенизатора M2M-100 от HuggingFace на чистом Kotlin, целиком на основе родного
 * `tokenizer.json` модели. Портируем руками, потому что готового артефакта под Android НЕТ:
 * `ai.djl.huggingface:tokenizers` тащит только десктопные нативы (linux/osx/win) без arm64-v8a `.so`,
 * а официальной Android-сборки HF `tokenizers` не существует.
 *
 * Конвейер (точно по спеке `tokenizer.json`, кроме отмеченного):
 *  1. Нормализация — NFKC. В референсе SentencePiece "Precompiled" charsmap; для разговорного текста
 *     RU/EN/ZH из демо NFKC — точное подмножество (свёртка по ширине/совместимости).
 *     Честно: экзотические кодпоинты могут токенизироваться чуть иначе, чем в референсе.
 *  2. Предтокенизация — WhitespaceSplit, затем Metaspace: каждое слово, отделённое пробелом,
 *     получает префикс-маркер `▁` (U+2581) (`add_prefix_space = true`).
 *  3. BPE по словам — `ignore_merges = true`, поэтому слово, уже целиком есть в vocab, выдаётся
 *     целиком; иначе обычное слияние пар по рангу, начиная с символов. `fuse_unk = true` сворачивает
 *     цепочки неизвестных символов в один `<unk>`. Без dropout и без byte-fallback.
 *  4. Постобработка — TemplateProcessing `single`: `[__src__] tokens </s>`.
 *
 * Декодирование разворачивает metaspace (`▁` → пробел) и выкидывает спецтокены.
 *
 * Id токенов берутся прямо из vocab, так что языковые токены M2M-100 (`__en__`=128022,
 * `__ru__`=128077, `__zh__`=128102) и управляющие id (`<s>`=0, `<pad>`=1, `</s>`=2, `<unk>`=3) точно
 * совпадают с референсными — на это и опирается логика forced-BOS у ONNX-декодера.
 */
class M2m100Tokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToToken: Map<Int, String>,
    private val mergeRanks: Map<Long, Int>,
    private val unkId: Int,
) {

    /** Кодирует исходный [text] для перевода НА [targetLang]: `[__src__] tokens </s>`. */
    fun encodeSource(text: String, sourceLang: String): IntArray {
        val srcLangId = langTokenId(sourceLang)
        val body = encodeToIds(text)
        // TemplateProcessing single = [__src__] A </s>
        val ids = ArrayList<Int>(body.size + 2)
        ids += srcLangId
        ids += body
        ids += EOS_ID
        return ids.toIntArray()
    }

    /** Принудительный первый генерируемый токен при переводе на [targetLang] (например, `__ru__`). */
    fun targetLangId(targetLang: String): Int = langTokenId(targetLang)

    /** Id языкового кода точно по vocab, например `"ru"` → id токена `__ru__`. */
    fun langTokenId(lang: String): Int {
        val token = "__${lang.lowercase()}__"
        return vocab[token]
            ?: throw IllegalArgumentException("unknown M2M-100 language token: $token")
    }

    /** Декодирует сгенерированные id обратно в текст, выкидывая спец-/управляющие/языковые токены. */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == EOS_ID || id == PAD_ID || id == BOS_ID || id == unkId) continue
            val token = idToToken[id] ?: continue
            if (isLanguageToken(token)) continue
            sb.append(token)
        }
        // Декод metaspace: `▁` — граница слова → пробел; ведущую границу обрезаем.
        return sb.toString().replace(METASPACE, ' ').trim()
    }

    val eosId: Int get() = EOS_ID
    val padId: Int get() = PAD_ID
    val decoderStartId: Int get() = DECODER_START_ID

    // ---- внутренности ----------------------------------------------------------------------

    private fun encodeToIds(text: String): List<Int> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val words = normalized.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val out = ArrayList<Int>()
        var lastWasUnk = false
        for (word in words) {
            val metaspaced = METASPACE + word
            for (piece in applyBpe(metaspaced)) {
                val id = vocab[piece]
                if (id != null) {
                    out += id
                    lastWasUnk = false
                } else if (!lastWasUnk) {
                    // Символа нет в vocab — fuse_unk сворачивает соседние неизвестные в один <unk>.
                    out += unkId
                    lastWasUnk = true
                }
            }
        }
        return out
    }

    /**
     * BPE для одного metaspace-слова. При `ignore_merges = true` слово, уже есть в vocab,
     * возвращается целиком; иначе режем на Unicode-кодпоинты (char coverage из SentencePiece) и
     * жадно сливаем соседнюю пару с минимальным рангом, пока такие пары есть.
     */
    private fun applyBpe(word: String): List<String> {
        if (vocab.containsKey(word)) return listOf(word)

        // Режем по Unicode-кодпоинтам (не по UTF-16 char), чтобы CJK/эмодзи не разваливались.
        val symbols = ArrayList<String>()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val charCount = Character.charCount(cp)
            symbols += word.substring(i, i + charCount)
            i += charCount
        }
        if (symbols.size <= 1) return symbols

        while (true) {
            var bestRank = Int.MAX_VALUE
            var bestIndex = -1
            for (j in 0 until symbols.size - 1) {
                val rank = mergeRanks[pairKey(symbols[j], symbols[j + 1])] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = j
                }
            }
            if (bestIndex < 0) break
            val merged = symbols[bestIndex] + symbols[bestIndex + 1]
            symbols[bestIndex] = merged
            symbols.removeAt(bestIndex + 1)
            if (symbols.size == 1) break
        }
        return symbols
    }

    private fun pairKey(left: String, right: String): Long {
        val l = vocab[left] ?: return ABSENT_PAIR
        val r = vocab[right] ?: return ABSENT_PAIR
        return (l.toLong() shl 32) or (r.toLong() and 0xFFFFFFFFL)
    }

    private fun isLanguageToken(token: String): Boolean =
        token.length >= 4 && token.startsWith("__") && token.endsWith("__")

    companion object {
        private const val TAG = "M2m100Tokenizer"
        const val METASPACE = '▁'
        private val WHITESPACE = Regex("\\s+")

        // Референсные управляющие id (стабильны для всего vocab M2M-100).
        private const val BOS_ID = 0
        private const val PAD_ID = 1
        private const val EOS_ID = 2
        // Декодер M2M-100 стартует с EOS id в роли decoder_start_token_id.
        private const val DECODER_START_ID = 2
        private const val ABSENT_PAIR = -1L

        /**
         * Загрузка из `tokenizer.json`. Возвращает null (никогда не бросает), если файла нет или он
         * структурно непригоден — чтобы вызывающий код мог честно заблокироваться.
         */
        fun load(tokenizerJson: File): M2m100Tokenizer? = runCatching {
            val root = JSONObject(tokenizerJson.readText())
            val model = root.getJSONObject("model")
            val vocabJson = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabJson.length() * 2)
            val idToToken = HashMap<Int, String>(vocabJson.length() * 2)
            val keys = vocabJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = vocabJson.getInt(key)
                vocab[key] = id
                idToToken[id] = key
            }
            // added_tokens (управляющие + языковые) переопределяют/дополняют базовый vocab.
            root.optJSONArray("added_tokens")?.let { added ->
                for (a in 0 until added.length()) {
                    val obj = added.getJSONObject(a)
                    val content = obj.getString("content")
                    val id = obj.getInt("id")
                    vocab[content] = id
                    idToToken[id] = content
                }
            }
            val merges = model.getJSONArray("merges")
            val mergeRanks = HashMap<Long, Int>(merges.length() * 2)
            for (m in 0 until merges.length()) {
                val entry = merges.get(m)
                val (left, right) = parseMerge(entry)
                val l = vocab[left]
                val r = vocab[right]
                if (l != null && r != null) {
                    mergeRanks[(l.toLong() shl 32) or (r.toLong() and 0xFFFFFFFFL)] = m
                }
            }
            val unkId = vocab["<unk>"] ?: 3
            Log.i(TAG, "tokenizer loaded: vocab=${vocab.size} merges=${mergeRanks.size}")
            M2m100Tokenizer(vocab, idToToken, mergeRanks, unkId)
        }.getOrElse { t ->
            Log.e(TAG, "tokenizer load failed", t)
            null
        }

        /** Записи merges — это либо строки "left right", либо массивы ["left","right"]. */
        private fun parseMerge(entry: Any): Pair<String, String> = when (entry) {
            is String -> {
                val sp = entry.indexOf(' ')
                entry.substring(0, sp) to entry.substring(sp + 1)
            }
            is org.json.JSONArray -> entry.getString(0) to entry.getString(1)
            else -> "" to ""
        }
    }
}
