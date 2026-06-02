import Foundation

// On-device перевод через MiLMMT-46-4B (архитектура Gemma-3) в формате Q6_K GGUF,
// крутится на вендоренном llama.cpp xcframework. Это QUALITY-уровень: пользователь
// может выбрать его наряду с M2M-100 (баланс) и Gemini (облако).
//
// Дисциплина A2: любой непустой TranslationResult.text — это РЕАЛЬНЫЙ вывод модели,
// раскодированный llama.cpp. Ничего не выдумываем: нет модели, недоступен фреймворк
// или сорвалось декодирование — честно отдаём unsupportedReason, а не фейковый текст.
//
// Модель и контекст создаём лениво при первом вызове и переиспользуем — загрузка 4B
// GGUF дорогая, это секунды. 4B-модель на телефоне/симуляторе медленная (секунды на
// перевод), но для quality-уровня это нормально.
// Тяжёлая работа идёт вне main actor — вызывающий уже уводит нас в фоновый поток через
// Thread.detachNewThread в LiveSessionModel.
//
// Формат промпта взят из карточки модели (xiaomi-research/MiLMMT-46-4B-v0.1):
//   Translate this from <Source> to <Target>:\n<Source>: <text>\n<Target>:
// Точная калька с Android-версии MilmmtMtProvider.
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

    // Промпт в completion-стиле из карточки MiLMMT, языки пишем полными английскими названиями.
    // Точная калька с Android MilmmtMtProvider.buildPrompt.
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

        // Сначала проверяем, что файл модели на месте — грузить впустую дорого.
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

// Распарсенное направление "src->tgt" в рамках демо-языков. Хранит полные английские
// названия, которые нужны промпту MiLMMT.
// Точная калька с Android MilmmtDirection.
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

    // Разбирает "en->ru"; nil, если формат битый или язык вне демо-набора RU/EN/ZH.
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
