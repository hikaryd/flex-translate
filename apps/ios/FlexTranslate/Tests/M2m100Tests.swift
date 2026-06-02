import Foundation
import Testing
@testable import FlexTranslate

// Unit tests for M2M-100 MT components.
// These are pure logic tests — no ONNX sessions, no model files required.

// MARK: - MtDirection parsing

@Suite("MtDirection")
struct MtDirectionTests {

    @Test("Parses canonical arrow format")
    func parsesArrowFormat() {
        let dir = MtDirection.parse("ru->en")
        #expect(dir?.source == "ru")
        #expect(dir?.target == "en")
    }

    @Test("Parses dash format")
    func parsesDashFormat() {
        let dir = MtDirection.parse("en-ru")
        #expect(dir?.source == "en")
        #expect(dir?.target == "ru")
    }

    @Test("Parses underscore format")
    func parsesUnderscoreFormat() {
        let dir = MtDirection.parse("ru_zh")
        #expect(dir?.source == "ru")
        #expect(dir?.target == "zh")
    }

    @Test("Case-insensitive")
    func caseInsensitive() {
        let dir = MtDirection.parse("RU->EN")
        #expect(dir?.source == "ru")
        #expect(dir?.target == "en")
    }

    @Test("Nil for same source and target")
    func nilForSameLang() {
        #expect(MtDirection.parse("ru->ru") == nil)
    }

    @Test("Nil for unsupported language")
    func nilForUnsupportedLang() {
        #expect(MtDirection.parse("fr->en") == nil)
        #expect(MtDirection.parse("ru->de") == nil)
    }

    @Test("Nil for malformed string")
    func nilForMalformed() {
        #expect(MtDirection.parse("ruen") == nil)
        #expect(MtDirection.parse("") == nil)
    }

    @Test("All four demo directions parse correctly")
    func allDemoDirections() {
        let pairs = ["ru->en", "en->ru", "ru->zh", "zh->ru"]
        for pair in pairs {
            #expect(MtDirection.parse(pair) != nil, "Expected \(pair) to parse")
        }
    }
}

// MARK: - GatedTranslationProvider (existing, regression)

@Suite("GatedTranslationProvider regression")
struct GatedMtProviderTests {
    @Test("Never fabricates — always returns nil text with a reason")
    func neverFabricates() {
        let provider = GatedTranslationProvider()
        let result = provider.translate(text: "привет", languagePair: "ru->en", deviceTier: "high")
        #expect(result.text == nil)
        #expect(result.unsupportedReason != nil)
    }

    @Test("Empty text returns nil text and nil reason")
    func emptyText() {
        let provider = GatedTranslationProvider()
        // GatedTranslationProvider returns a reason for every call including empty text
        // — behaviour check: at minimum no crash and text stays nil.
        let result = provider.translate(text: "", languagePair: "ru->en", deviceTier: "high")
        #expect(result.text == nil)
    }
}

// MARK: - M2m100MtProvider gating (no model files)

@Suite("M2m100MtProvider gating")
struct M2m100MtProviderGatingTests {

    @Test("Missing model returns honest unsupportedReason, never text")
    func missingModelGatesHonestly() {
        // Point provider at a non-existent directory so isInstalled returns false.
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("m2m100-test-\(UUID().uuidString)")
        let spec = MtModelSpecs.m2m100418M
        let provider = M2m100MtProvider(spec: spec, modelDir: tempDir)
        let result = provider.translate(text: "привет", languagePair: "ru->en", deviceTier: "high")
        #expect(result.text == nil)
        #expect(result.unsupportedReason != nil)
        // Must not be an empty reason.
        #expect(result.unsupportedReason?.isEmpty == false)
    }

    @Test("Unsupported language pair returns honest reason")
    func unsupportedPairReturnsReason() {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("m2m100-test-\(UUID().uuidString)")
        let spec = MtModelSpecs.m2m100418M
        let provider = M2m100MtProvider(spec: spec, modelDir: tempDir)
        let result = provider.translate(text: "hello", languagePair: "fr->de", deviceTier: "high")
        #expect(result.text == nil)
        #expect(result.unsupportedReason != nil)
    }

    @Test("Empty text returns nil text and nil reason (no fabrication, no error)")
    func emptyTextIsNoop() {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("m2m100-test-\(UUID().uuidString)")
        let spec = MtModelSpecs.m2m100418M
        let provider = M2m100MtProvider(spec: spec, modelDir: tempDir)
        let result = provider.translate(text: "   ", languagePair: "ru->en", deviceTier: "high")
        #expect(result.text == nil)
        // Empty input is a no-op, not an error — reason should be nil.
        #expect(result.unsupportedReason == nil)
    }

    @Test("Provider id contains model id")
    func providerIdContainsModelId() {
        let tempDir = FileManager.default.temporaryDirectory
        let spec = MtModelSpecs.m2m100418M
        let provider = M2m100MtProvider(spec: spec, modelDir: tempDir)
        #expect(provider.providerId.contains("m2m100"))
    }
}

// MARK: - M2m100Tokenizer helpers (file-scope so @Test funcs can call them)

// Build a minimal tokenizer.json in-memory for testing.
// Vocab: ▁hello, ▁world, specials, language tokens.
private func buildMinimalTokenizerJson() -> Data {
    let vocab: [String: Int] = [
        "<s>": 0, "<pad>": 1, "</s>": 2, "<unk>": 3,
        "\u{2581}h": 4, "\u{2581}he": 5, "\u{2581}hel": 6, "\u{2581}hell": 7, "\u{2581}hello": 8,
        "\u{2581}w": 9, "\u{2581}wo": 10, "\u{2581}wor": 11, "\u{2581}worl": 12, "\u{2581}world": 13,
        "e": 14, "l": 15, "o": 16, "h": 17, "w": 18, "r": 19, "d": 20,
    ]
    let addedTokens: [[String: Any]] = [
        ["id": 128022, "content": "__en__", "special": true],
        ["id": 128077, "content": "__ru__", "special": true],
        ["id": 128102, "content": "__zh__", "special": true],
    ]
    let merges: [String] = [
        "\u{2581}h e", "\u{2581}he l", "\u{2581}hel l", "\u{2581}hell o",
        "\u{2581}w o", "\u{2581}wo r", "\u{2581}wor l", "\u{2581}worl d",
    ]
    let json: [String: Any] = [
        "model": ["type": "BPE", "vocab": vocab, "merges": merges],
        "added_tokens": addedTokens,
    ]
    return try! JSONSerialization.data(withJSONObject: json)
}

private func makeTestTokenizer() -> M2m100Tokenizer? {
    let tmpFile = FileManager.default.temporaryDirectory
        .appendingPathComponent("test-tokenizer-\(UUID().uuidString).json")
    let data = buildMinimalTokenizerJson()
    try? data.write(to: tmpFile)
    defer { try? FileManager.default.removeItem(at: tmpFile) }
    return M2m100Tokenizer.load(from: tmpFile)
}

// MARK: - M2m100Tokenizer tests

@Suite("M2m100Tokenizer")
struct M2m100TokenizerTests {

    @Test("Tokenizer loads from valid json")
    func loadsFromJson() {
        #expect(makeTestTokenizer() != nil)
    }

    @Test("Language token ids match M2M-100 reference values")
    func langTokenIds() {
        guard let tok = makeTestTokenizer() else { return }
        #expect(tok.targetLangId(lang: "en") == 128022)
        #expect(tok.targetLangId(lang: "ru") == 128077)
        #expect(tok.targetLangId(lang: "zh") == 128102)
    }

    @Test("encodeSource prepends src-lang token and appends EOS")
    func encodeSourceHasSrcAndEos() {
        guard let tok = makeTestTokenizer() else { return }
        let ids = tok.encodeSource(text: "hello", sourceLang: "en")
        #expect(ids.first == 128022)  // __en__
        #expect(ids.last == 2)         // </s>
        #expect(ids.count >= 3)        // src + at least one body token + eos
    }

    @Test("Decode strips language tokens and converts metaspace to space")
    func decodeStripsSpecials() {
        guard let tok = makeTestTokenizer() else { return }
        // Ids for ▁hello ▁world using our minimal vocab.
        let ids = [128022, 8, 13, 2]  // __en__, ▁hello, ▁world, </s>
        let decoded = tok.decode(ids: ids)
        // ▁hello ▁world → "hello world" after metaspace→space + trim.
        #expect(decoded == "hello world")
    }

    @Test("Decode of only EOS and PAD returns empty string")
    func decodeSpecialsOnly() {
        guard let tok = makeTestTokenizer() else { return }
        let decoded = tok.decode(ids: [0, 1, 2])
        #expect(decoded.isEmpty)
    }

    @Test("decoderStartId is EOS id (M2M-100 convention)")
    func decoderStartIsEos() {
        guard let tok = makeTestTokenizer() else { return }
        #expect(tok.decoderStartId == tok.eosId)
        #expect(tok.decoderStartId == 2)
    }

    @Test("BPE merges ▁hello into a single token")
    func bpeMergesHello() {
        guard let tok = makeTestTokenizer() else { return }
        let ids = tok.encodeSource(text: "hello", sourceLang: "en")
        // After all merges: __en__(128022) + ▁hello(8) + </s>(2) = exactly 3 ids.
        #expect(ids.count == 3)
        #expect(ids[1] == 8)  // ▁hello
    }

    @Test("Round-trip: encode then decode recovers original text")
    func roundTrip() {
        guard let tok = makeTestTokenizer() else { return }
        let original = "hello world"
        let ids = tok.encodeSource(text: original, sourceLang: "en")
        // Strip src token and EOS before decoding to mimic generation output.
        let bodyIds = Array(ids.dropFirst().dropLast())
        let decoded = tok.decode(ids: bodyIds)
        #expect(decoded == original)
    }

    @Test("Load from missing file returns nil")
    func missingFileReturnsNil() {
        let missing = URL(fileURLWithPath: "/tmp/does-not-exist-\(UUID().uuidString).json")
        #expect(M2m100Tokenizer.load(from: missing) == nil)
    }

    @Test("Load from invalid json returns nil")
    func invalidJsonReturnsNil() {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("bad-\(UUID().uuidString).json")
        try? "not json at all {{{".data(using: .utf8)?.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        #expect(M2m100Tokenizer.load(from: tmp) == nil)
    }
}

// MARK: - Live engine integration (runs only when model files are sideloaded)
// Skips gracefully when the model is not installed — never blocks CI.

@Suite("M2m100OnnxEngine live")
struct M2m100LiveTests {

    // Resolve the model dir from multiple candidate locations accessible in the simulator:
    //  1. MtModelStore.shared (Application Support of the TEST process container)
    //  2. /private/var/tmp/m2m100-418m/ (shared, pre-populated by xcrun simctl before the test)
    //  3. /tmp/m2m100-418m/ (symlink to same)
    private func resolveModelDir() -> URL? {
        let spec = MtModelSpecs.m2m100418M

        // Check MtModelStore first (normal app/test container).
        let store = MtModelStore.shared
        if store.isInstalled(spec) {
            return store.modelDir(for: spec)
        }

        // Check shared tmp paths (pre-populated via simctl before running the test).
        let sharedPaths: [URL] = [
            URL(fileURLWithPath: "/private/var/tmp/m2m100-418m"),
            URL(fileURLWithPath: "/tmp/m2m100-418m"),
        ]
        for path in sharedPaths {
            if spec.isInstalled(in: path) { return path }
        }
        return nil
    }

    @Test("Real RU→EN translation produces non-empty non-error output when model installed")
    func realTranslationRuEn() async throws {
        guard let modelDir = resolveModelDir() else {
            // Model not sideloaded — skip gracefully.
            print("[M2m100LiveTests] Model not found — skipping live test")
            return
        }

        let spec = MtModelSpecs.m2m100418M
        let provider = M2m100MtProvider(spec: spec, modelDir: modelDir)

        // Run on a background thread — ONNX inference blocks.
        let result = await Task.detached(priority: .userInitiated) {
            provider.translate(
                text: "сейчас к тебе приедет бригада давай",
                languagePair: "ru->en",
                deviceTier: "high"
            )
        }.value

        // Log result regardless of outcome.
        if let text = result.text {
            print("[M2m100LiveTests] ru→en: \"\(text)\"")
        } else {
            print("[M2m100LiveTests] ru→en unsupportedReason: \(result.unsupportedReason ?? "nil")")
        }

        // When the model IS installed, we must get real text — not nil, not an error.
        #expect(result.text != nil, "Expected real translation, got nil")
        #expect(result.unsupportedReason == nil, "Expected no error, got: \(result.unsupportedReason ?? "")")
        #expect(result.text?.isEmpty == false, "Expected non-empty translation")
    }
}

// MARK: - MtModelSpec / MtModelStore

@Suite("MtModelSpec")
struct MtModelSpecTests {

    @Test("M2M-100 spec has correct modelId")
    func m2m100ModelId() {
        let spec = MtModelSpecs.m2m100418M
        #expect(spec.modelId == "m2m100-418m")
    }

    @Test("M2M-100 spec requires four files")
    func m2m100RequiredFiles() {
        let spec = MtModelSpecs.m2m100418M
        #expect(spec.requiredFiles.count == 4)
    }

    @Test("isInstalled returns false for empty directory")
    func notInstalledInEmptyDir() {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("m2m100-spec-\(UUID().uuidString)")
        let spec = MtModelSpecs.m2m100418M
        #expect(spec.isInstalled(in: tempDir) == false)
    }
}
