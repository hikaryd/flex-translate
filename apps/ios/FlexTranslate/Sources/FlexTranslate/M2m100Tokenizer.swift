import Foundation

// Точный порт быстрого токенизатора M2M-100 из HuggingFace на чистый Swift,
// полностью работающий по родному `tokenizer.json` модели. Повторяет
// андроидный M2m100Tokenizer один в один.
//
// Пайплайн:
//  1. Нормализация — NFKC (Unicode). Для разговорного RU/EN/ZH это честное
//     подмножество эталонного SentencePiece Precompiled charsmap (свёртка ширины/совместимости).
//  2. Пре-токенизация — WhitespaceSplit, затем Metaspace: каждое слово получает
//     префикс ▁ (U+2581), add_prefix_space = true.
//  3. BPE по словам — ignore_merges = true: слово, уже лежащее в vocab, отдаём
//     целиком; иначе обычное слияние пар по рангу, начиная с кодпоинтов.
//     fuse_unk = true схлопывает подряд идущие unknown в один <unk>. Без dropout,
//     без byte-fallback.
//  4. Пост-обработка — TemplateProcessing single: [__src__] tokens </s>.
//
// Декодирование: ▁ → пробел, обрезаем пробелы по краям, спец-токены пропускаем.
//
// Id токенов берём из vocab, поэтому языковые токены (__en__=128022, __ru__=128077,
// __zh__=128102) и управляющие id (<s>=0, <pad>=1, </s>=2, <unk>=3) совпадают с эталоном.
final class M2m100Tokenizer {
    // MARK: - Public constants
    static let metaspace: Character = "\u{2581}" // ▁

    static let bosId = 0
    static let padId = 1
    static let eosId = 2
    // Декодер M2M-100 стартует с EOS в роли decoder_start_token_id.
    static let decoderStartId = 2

    var eosId: Int { M2m100Tokenizer.eosId }
    var padId: Int { M2m100Tokenizer.padId }
    var decoderStartId: Int { M2m100Tokenizer.decoderStartId }

    // MARK: - Private state
    private let vocab: [String: Int]
    private let idToToken: [Int: String]
    // Ключ кодирует пару как (leftId << 32) | rightId; значение — ранг слияния (меньше = приоритетнее).
    private let mergeRanks: [UInt64: Int]
    private let unkId: Int

    private static let absentPair: UInt64 = UInt64.max

    // MARK: - Init
    private init(vocab: [String: Int], idToToken: [Int: String], mergeRanks: [UInt64: Int], unkId: Int) {
        self.vocab = vocab
        self.idToToken = idToToken
        self.mergeRanks = mergeRanks
        self.unkId = unkId
    }

    // MARK: - Public API

    // Кодирует исходный текст для перевода с sourceLang: [__src__] tokens </s>
    func encodeSource(text: String, sourceLang: String) -> [Int] {
        let srcLangId = langTokenId(lang: sourceLang)
        let body = encodeToIds(text: text)
        var ids = [Int]()
        ids.reserveCapacity(body.count + 2)
        ids.append(srcLangId)
        ids.append(contentsOf: body)
        ids.append(M2m100Tokenizer.eosId)
        return ids
    }

    // Принудительный первый токен генерации под целевой язык, например "ru" → id токена __ru__.
    func targetLangId(lang: String) -> Int {
        langTokenId(lang: lang)
    }

    // Декодирует сгенерированные id обратно в текст: спецтокены пропускаем, ▁ → пробел, обрезаем края.
    func decode(ids: [Int]) -> String {
        var sb = ""
        for id in ids {
            if id == M2m100Tokenizer.eosId || id == M2m100Tokenizer.padId ||
               id == M2m100Tokenizer.bosId || id == unkId { continue }
            guard let token = idToToken[id] else { continue }
            if isLanguageToken(token) { continue }
            sb.append(token)
        }
        // ▁ — граница слова → пробел; ведущую границу обрезаем.
        return sb.replacingOccurrences(of: "\u{2581}", with: " ").trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Load

    // Грузит из tokenizer.json. Возвращает nil (никогда не бросает), если файла нет или он битый.
    static func load(from url: URL) -> M2m100Tokenizer? {
        guard let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let model = root["model"] as? [String: Any],
              let vocabJson = model["vocab"] as? [String: Any] else {
            return nil
        }

        var vocab = [String: Int](minimumCapacity: vocabJson.count * 2)
        var idToToken = [Int: String](minimumCapacity: vocabJson.count * 2)
        for (key, value) in vocabJson {
            guard let id = value as? Int else { continue }
            vocab[key] = id
            idToToken[id] = key
        }

        // added_tokens (управляющие + языковые) дополняют/перекрывают базовый vocab.
        if let addedTokens = root["added_tokens"] as? [[String: Any]] {
            for obj in addedTokens {
                guard let content = obj["content"] as? String,
                      let id = obj["id"] as? Int else { continue }
                vocab[content] = id
                idToToken[id] = content
            }
        }

        guard let mergesRaw = model["merges"] as? [Any] else { return nil }
        var mergeRanks = [UInt64: Int](minimumCapacity: mergesRaw.count * 2)
        for (rank, entry) in mergesRaw.enumerated() {
            guard let (left, right) = parseMerge(entry) else { continue }
            guard let l = vocab[left], let r = vocab[right] else { continue }
            let key = (UInt64(bitPattern: Int64(l)) << 32) | (UInt64(bitPattern: Int64(r)) & 0xFFFF_FFFF)
            mergeRanks[key] = rank
        }

        let unkId = vocab["<unk>"] ?? 3
        return M2m100Tokenizer(vocab: vocab, idToToken: idToToken, mergeRanks: mergeRanks, unkId: unkId)
    }

    // MARK: - Private helpers

    private func langTokenId(lang: String) -> Int {
        let token = "__\(lang.lowercased())__"
        guard let id = vocab[token] else {
            // Незнакомый языковой токен — возвращаем unk. Вызывающие сами отсекают неподдерживаемые языки.
            return unkId
        }
        return id
    }

    private func encodeToIds(text: String) -> [Int] {
        // NFKC-нормализация повторяет андроидный Normalizer.normalize(text, NFKC).
        let normalized = text.precomposedStringWithCompatibilityMapping
        let words = normalized.split(separator: " ", omittingEmptySubsequences: true)
            .map(String.init)
        var out = [Int]()
        var lastWasUnk = false
        let metaspaceStr = String(M2m100Tokenizer.metaspace)
        for word in words {
            let metaspaced = metaspaceStr + word
            for piece in applyBpe(word: metaspaced) {
                if let id = vocab[piece] {
                    out.append(id)
                    lastWasUnk = false
                } else if !lastWasUnk {
                    // fuse_unk: подряд идущие unknown схлопываем в один <unk>.
                    out.append(unkId)
                    lastWasUnk = true
                }
            }
        }
        return out
    }

    // BPE по одному metaspace-слову. При ignore_merges = true слово, уже лежащее
    // в vocab, отдаём целиком; иначе бьём на Unicode-скаляры и жадно сливаем по
    // наименьшему рангу, пока есть что сливать.
    private func applyBpe(word: String) -> [String] {
        if vocab[word] != nil { return [word] }

        // Бьём на кластеры Unicode-скаляров (повторяет андроидный цикл по codePointAt).
        var symbols = unicodeScalarClusters(word)
        if symbols.count <= 1 { return symbols }

        while true {
            var bestRank = Int.max
            var bestIndex = -1
            for j in 0 ..< symbols.count - 1 {
                if let rank = mergeRank(left: symbols[j], right: symbols[j + 1]) {
                    if rank < bestRank {
                        bestRank = rank
                        bestIndex = j
                    }
                }
            }
            if bestIndex < 0 { break }
            symbols[bestIndex] = symbols[bestIndex] + symbols[bestIndex + 1]
            symbols.remove(at: bestIndex + 1)
            if symbols.count == 1 { break }
        }
        return symbols
    }

    private func mergeRank(left: String, right: String) -> Int? {
        guard let l = vocab[left], let r = vocab[right] else { return nil }
        let key = (UInt64(bitPattern: Int64(l)) << 32) | (UInt64(bitPattern: Int64(r)) & 0xFFFF_FFFF)
        return mergeRanks[key]
    }

    // Разбивает строку на отдельные Unicode-скаляры (суррогатные пары / CJK не ломает).
    private func unicodeScalarClusters(_ s: String) -> [String] {
        s.unicodeScalars.map { String($0) }
    }

    private func isLanguageToken(_ token: String) -> Bool {
        token.count >= 4 && token.hasPrefix("__") && token.hasSuffix("__")
    }

    // Разбирает запись merge: либо строка "left right", либо массив ["left","right"].
    private static func parseMerge(_ entry: Any) -> (String, String)? {
        if let s = entry as? String {
            guard let spaceIdx = s.firstIndex(of: " ") else { return nil }
            let left = String(s[s.startIndex ..< spaceIdx])
            let right = String(s[s.index(after: spaceIdx)...])
            return (left, right)
        }
        if let arr = entry as? [String], arr.count >= 2 {
            return (arr[0], arr[1])
        }
        return nil
    }
}
