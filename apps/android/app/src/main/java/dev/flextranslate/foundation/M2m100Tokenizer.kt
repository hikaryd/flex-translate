package dev.flextranslate.foundation

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Faithful pure-Kotlin port of the M2M-100 HuggingFace fast tokenizer, driven entirely by the
 * model's own `tokenizer.json`. We port it by hand because there is NO usable Android tokenizer
 * artifact: `ai.djl.huggingface:tokenizers` bundles only desktop natives (linux/osx/win) with no
 * arm64-v8a `.so`, and no official HF `tokenizers` Android build exists.
 *
 * Pipeline (matches the `tokenizer.json` spec exactly except where noted):
 *  1. Normalize — NFKC. The reference uses a SentencePiece "Precompiled" charsmap; for the
 *     RU/EN/ZH conversational demo text NFKC is the faithful subset (width/compatibility folding).
 *     Documented honestly: exotic codepoints may tokenize slightly differently than the reference.
 *  2. Pre-tokenize — WhitespaceSplit then Metaspace: each whitespace-separated word is prefixed
 *     with the metaspace marker `▁` (U+2581) (`add_prefix_space = true`).
 *  3. BPE per word — `ignore_merges = true` so a word already present verbatim in the vocab is
 *     emitted whole; otherwise standard rank-ordered pair merging from chars up. `fuse_unk = true`
 *     folds runs of unknown symbols into a single `<unk>`. No dropout, no byte-fallback.
 *  4. Post-process — TemplateProcessing `single`: `[__src__] tokens </s>`.
 *
 * Decoding reverses metaspace (`▁` → space) and strips special tokens.
 *
 * Token ids come straight from the vocab map, so the M2M-100 language tokens (`__en__`=128022,
 * `__ru__`=128077, `__zh__`=128102) and control ids (`<s>`=0, `<pad>`=1, `</s>`=2, `<unk>`=3) are
 * exactly the reference ids — which is what the ONNX decoder's forced-BOS logic depends on.
 */
class M2m100Tokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToToken: Map<Int, String>,
    private val mergeRanks: Map<Long, Int>,
    private val unkId: Int,
) {

    /** Encode source [text] for translation INTO [targetLang]: `[__src__] tokens </s>`. */
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

    /** The forced first generated token for translating into [targetLang] (e.g. `__ru__`). */
    fun targetLangId(targetLang: String): Int = langTokenId(targetLang)

    /** Vocabulary-faithful id for a language code, e.g. `"ru"` → id of `__ru__`. */
    fun langTokenId(lang: String): Int {
        val token = "__${lang.lowercase()}__"
        return vocab[token]
            ?: throw IllegalArgumentException("unknown M2M-100 language token: $token")
    }

    /** Decode generated ids back to text, dropping special/control/language tokens. */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == EOS_ID || id == PAD_ID || id == BOS_ID || id == unkId) continue
            val token = idToToken[id] ?: continue
            if (isLanguageToken(token)) continue
            sb.append(token)
        }
        // Metaspace decode: `▁` marks a word boundary → space; leading boundary trimmed.
        return sb.toString().replace(METASPACE, ' ').trim()
    }

    val eosId: Int get() = EOS_ID
    val padId: Int get() = PAD_ID
    val decoderStartId: Int get() = DECODER_START_ID

    // ---- internals -------------------------------------------------------------------------

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
                    // Symbol absent from vocab — fuse_unk folds adjacent unknowns into one <unk>.
                    out += unkId
                    lastWasUnk = true
                }
            }
        }
        return out
    }

    /**
     * BPE on a single metaspaced word. With `ignore_merges = true`, a word already in the vocab
     * is returned whole; otherwise we split into Unicode codepoints (SentencePiece char coverage)
     * and greedily merge the lowest-rank adjacent pair until none remain.
     */
    private fun applyBpe(word: String): List<String> {
        if (vocab.containsKey(word)) return listOf(word)

        // Split into Unicode code points (not UTF-16 chars) so CJK/emoji stay intact.
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

        // Reference control ids (stable across the M2M-100 vocab).
        private const val BOS_ID = 0
        private const val PAD_ID = 1
        private const val EOS_ID = 2
        // M2M-100 decoder is seeded with the EOS id as the decoder_start_token_id.
        private const val DECODER_START_ID = 2
        private const val ABSENT_PAIR = -1L

        /**
         * Load from a `tokenizer.json`. Returns null (never throws) if the file is missing or
         * structurally unusable, so callers can gate honestly.
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
            // added_tokens (control + language tokens) override/augment the base vocab map.
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

        /** merges entries are either "left right" strings or ["left","right"] arrays. */
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
