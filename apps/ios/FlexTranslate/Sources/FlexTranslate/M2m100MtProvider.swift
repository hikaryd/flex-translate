import Foundation

// Real on-device machine translation via M2M-100 (M2m100OnnxEngine) conforming to
// TranslationProvider.
//
// A2 discipline: every non-nil TranslationResult.text is GENUINE model output.
// No fabricated/canned translation anywhere. If model files are absent or a
// translation fails, the provider returns an honest unsupportedReason, never fake text.
//
// The engine is created lazily on first use and reused across calls (loading three
// ONNX sessions is expensive). Direction comes from a "src->tgt" languagePair string,
// e.g. "ru->en". M2M-100 handles RU↔EN and RU↔ZH via a forced target-language token
// — no English pivot required.
//
// Not @MainActor: translate() may be called from any thread; lazy engine init is
// protected by a lock. State transitions are monotone (Uninitialized → one terminal).
final class M2m100MtProvider: TranslationProvider {
    let providerId: String

    private let spec: MtModelSpec
    private let modelDir: URL

    private enum State {
        case uninitialized
        case ready(M2m100OnnxEngine)
        case missingModel
        case failed(String)
    }

    private let stateLock = NSLock()
    private var _state: State = .uninitialized

    init(spec: MtModelSpec, modelDir: URL) {
        self.spec = spec
        self.modelDir = modelDir
        self.providerId = "m2m100:\(spec.modelId)"
    }

    func translate(text: String, languagePair: String, deviceTier: String) -> TranslationResult {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            return TranslationResult(text: nil, unsupportedReason: nil)
        }

        guard let direction = MtDirection.parse(languagePair) else {
            return TranslationResult(
                text: nil,
                unsupportedReason: "пара \(languagePair) не поддерживается этой моделью"
            )
        }

        switch ensureEngine() {
        case .ready(let engine):
            return runTranslate(engine: engine, text: trimmed, direction: direction)
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
        MtModelStore.shared.isInstalled(spec)
    }

    func close() {
        stateLock.lock()
        let current = _state
        _state = .uninitialized
        stateLock.unlock()
        if case .ready(let engine) = current {
            engine.close()
        }
    }

    // MARK: - Private

    private func runTranslate(
        engine: M2m100OnnxEngine,
        text: String,
        direction: MtDirection
    ) -> TranslationResult {
        guard let output = engine.translate(
            text: text,
            sourceLang: direction.source,
            targetLang: direction.target
        ) else {
            return TranslationResult(
                text: nil,
                unsupportedReason: "ошибка перевода: движок вернул nil"
            )
        }
        return TranslationResult(text: output, unsupportedReason: nil)
    }

    private func ensureEngine() -> State {
        stateLock.lock()
        let current = _state
        stateLock.unlock()

        if case .uninitialized = current {} else { return current }

        if !MtModelStore.shared.isInstalled(spec) {
            stateLock.lock()
            _state = .missingModel
            stateLock.unlock()
            return .missingModel
        }

        let dir = MtModelStore.shared.modelDir(for: spec)
        if let engine = M2m100OnnxEngine.create(spec: spec, modelDir: dir) {
            let next = State.ready(engine)
            stateLock.lock()
            _state = next
            stateLock.unlock()
            return next
        } else {
            let next = State.failed("session init returned nil")
            stateLock.lock()
            _state = next
            stateLock.unlock()
            return next
        }
    }
}

// MARK: - Direction parsing

// A parsed "src->tgt" translation direction restricted to M2M-100 demo language codes.
struct MtDirection: Equatable {
    let source: String
    let target: String

    private static let supported: Set<String> = ["ru", "en", "zh"]

    // Parse "en->ru"; nil when malformed or outside the RU/EN/ZH demo scope.
    static func parse(_ languagePair: String) -> MtDirection? {
        // Accept "->" or "-" or "_" as separator; take at most 2 parts.
        let separators: [String] = ["->", "-", "_"]
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
        guard supported.contains(source), supported.contains(target), source != target else {
            return nil
        }
        return MtDirection(source: source, target: target)
    }
}
