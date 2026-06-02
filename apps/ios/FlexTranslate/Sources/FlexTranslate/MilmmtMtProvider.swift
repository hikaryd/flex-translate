import Foundation

// Real on-device machine translation via MiLMMT-46-4B (Gemma-3 architecture) Q6_K GGUF,
// run through the vendored llama.cpp xcframework, conforming to TranslationProvider.
// This is the QUALITY tier the user can select alongside M2M-100 (balanced) and Gemini (cloud).
//
// A2 discipline: every non-nil TranslationResult.text is GENUINE model output decoded by
// llama.cpp. Nothing is fabricated — a missing model, unavailable framework, or decode
// failure surfaces an honest TranslationResult.unsupportedReason, never fake text.
//
// The model + context are created lazily on first use and reused across calls
// (loading a 4B GGUF is expensive — seconds). A 4B LLM on mobile/simulator is slow
// (seconds per translation); that is acceptable for the quality tier.
// Heavy work runs off the main actor — the caller already dispatches to a background thread
// via Thread.detachNewThread in LiveSessionModel.
//
// Prompt format from the MiLMMT model card (xiaomi-research/MiLMMT-46-4B-v0.1):
//   Translate this from <Source> to <Target>:\n<Source>: <text>\n<Target>:
// Mirrors Android MilmmtMtProvider exactly.
final class MilmmtMtProvider: TranslationProvider {
    let providerId: String

    private let spec: MtModelSpec.GgufConfig
    private let modelDir: URL

    private enum State {
        case uninitialized
        case ready(OpaquePointer)
        case missingModel
        case failed(String)
    }

    private let stateLock = NSLock()
    private var _state: State = .uninitialized

    init(spec: MtModelSpec.GgufConfig, modelDir: URL) {
        self.spec = spec
        self.modelDir = modelDir
        self.providerId = "milmmt:\(spec.modelId)"
    }

    // MARK: - TranslationProvider

    func translate(text: String, languagePair: String, deviceTier: String) -> TranslationResult {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            return TranslationResult(text: nil, unsupportedReason: nil)
        }

        guard let direction = MilmmtDirection.parse(languagePair) else {
            return TranslationResult(
                text: nil,
                unsupportedReason: "пара \(languagePair) не поддерживается этой моделью"
            )
        }

        switch ensureModel() {
        case .ready(let handle):
            return runTranslate(handle: handle, text: trimmed, direction: direction)
        case .missingModel:
            return TranslationResult(
                text: nil,
                unsupportedReason: "MT-модель \(spec.modelId) не установлена"
            )
        case .failed(let reason):
            return TranslationResult(
                text: nil,
                unsupportedReason: "MT-движок недоступен: \(reason)"
            )
        case .uninitialized:
            return TranslationResult(
                text: nil,
                unsupportedReason: "MT-движок не инициализирован"
            )
        }
    }

    var isModelInstalled: Bool {
        MtModelSpec.gguf(spec).isInstalled(in: modelDir)
    }

    func close() {
        stateLock.lock()
        let current = _state
        _state = .uninitialized
        stateLock.unlock()
        if case .ready(let handle) = current {
            LlamaCppBridge.free(handle: handle)
        }
    }

    // MARK: - Private

    private func runTranslate(handle: OpaquePointer, text: String, direction: MilmmtDirection) -> TranslationResult {
        let prompt = buildPrompt(text: text, direction: direction)
        guard let output = LlamaCppBridge.generate(
            handle: handle,
            prompt: prompt,
            maxNewTokens: LlamaCppBridge.maxNewTokens
        ) else {
            return TranslationResult(
                text: nil,
                unsupportedReason: "ошибка генерации llama.cpp"
            )
        }
        let cleaned = output.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty {
            return TranslationResult(text: nil, unsupportedReason: "пустой ответ модели")
        }
        return TranslationResult(text: cleaned, unsupportedReason: nil)
    }

    // Completion-style MT prompt from the MiLMMT model card; full English language names.
    // Mirrors Android MilmmtMtProvider.buildPrompt exactly.
    private func buildPrompt(text: String, direction: MilmmtDirection) -> String {
        "Translate this from \(direction.sourceName) to \(direction.targetName):\n" +
        "\(direction.sourceName): \(text)\n" +
        "\(direction.targetName):"
    }

    private func ensureModel() -> State {
        stateLock.lock()
        let current = _state
        stateLock.unlock()

        if case .uninitialized = current {} else { return current }

        // Check model file is present before attempting expensive load.
        if !MtModelSpec.gguf(spec).isInstalled(in: modelDir) {
            stateLock.lock()
            _state = .missingModel
            stateLock.unlock()
            return .missingModel
        }

        let ggufPath = modelDir.appendingPathComponent(spec.gguf).path
        if let handle = LlamaCppBridge.load(
            path: ggufPath,
            nThreads: LlamaCppBridge.defaultThreads,
            nCtx: LlamaCppBridge.defaultCtx
        ) {
            let next = State.ready(handle)
            stateLock.lock()
            _state = next
            stateLock.unlock()
            return next
        } else {
            let next = State.failed("llama.cpp model load returned nil")
            stateLock.lock()
            _state = next
            stateLock.unlock()
            return next
        }
    }
}

// MARK: - Direction parsing

// A parsed "src->tgt" direction restricted to the demo language codes, carrying the
// full English language names MiLMMT's prompt requires.
// Mirrors Android MilmmtDirection exactly.
struct MilmmtDirection: Equatable {
    let source: String
    let target: String
    let sourceName: String
    let targetName: String

    private static let languageNames: [String: String] = [
        "ru": "Russian",
        "en": "English",
        "zh": "Chinese (Simplified)",
    ]

    // Parse "en->ru"; nil when malformed or outside the RU/EN/ZH demo scope.
    static func parse(_ languagePair: String) -> MilmmtDirection? {
        let separators = ["->", "-", "_"]
        var parts: [String]?
        for sep in separators {
            let split = languagePair.components(separatedBy: sep)
            if split.count == 2 {
                parts = split
                break
            }
        }
        guard let parts else { return nil }
        let source = parts[0].trimmingCharacters(in: .whitespaces).lowercased()
        let target = parts[1].trimmingCharacters(in: .whitespaces).lowercased()
        guard
            let sourceName = languageNames[source],
            let targetName = languageNames[target],
            source != target
        else { return nil }
        return MilmmtDirection(source: source, target: target,
                               sourceName: sourceName, targetName: targetName)
    }
}
