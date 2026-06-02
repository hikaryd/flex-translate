import Foundation

// On-device перевод через M2M-100 (M2m100OnnxEngine), реализует TranslationProvider.
//
// Дисциплина A2: любой ненулевой TranslationResult.text — настоящий вывод модели.
// Никаких заготовленных/выдуманных переводов. Если файлов модели нет или перевод
// сорвался — отдаём честный unsupportedReason, но не фальшивый текст.
//
// Движок создаём лениво при первом вызове и переиспользуем: поднять три ONNX-сессии
// дорого. Направление приходит строкой "src->tgt", например "ru->en". M2M-100 делает
// RU↔EN и RU↔ZH через forced target-language token — пивот через английский не нужен.
//
// Не @MainActor: translate() могут позвать из любого потока, ленивую инициализацию
// движка прикрывает lock. Переходы состояния монотонные (uninitialized → один терминал).
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
        spec.isInstalled(in: modelDir)
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

        // Проверяем установку по modelDir из конструктора: тогда тесты, указывающие
        // на несуществующую temp-папку, чисто получают .missingModel — без реальных
        // файлов модели в MtModelStore.
        if !spec.isInstalled(in: modelDir) {
            stateLock.lock()
            _state = .missingModel
            stateLock.unlock()
            return .missingModel
        }

        if let engine = M2m100OnnxEngine.create(spec: spec, modelDir: modelDir) {
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

// Разобранное направление "src->tgt", ограниченное демо-набором языков M2M-100.
struct MtDirection: Equatable {
    let source: String
    let target: String

    private static let supported: Set<String> = ["ru", "en", "zh"]

    // Парсит "en->ru"; nil если кривой формат или язык вне демо-набора RU/EN/ZH.
    static func parse(_ languagePair: String) -> MtDirection? {
        // Разделитель — "->", "-" или "_"; берём не больше 2 частей.
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
