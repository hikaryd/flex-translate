import Foundation
import Network

/// Менеджер загрузки моделей прямо в приложении. Тянет файлы пака с их исходных URL в тот
/// же корень Application Support/models/<modelId>/, откуда читают AsrModelStore / MtModelStore,
/// так что завершённая загрузка сразу видна рантайму.
///
/// На каждый пак отдаёт наблюдаемый DownloadState, который рисует экран Models:
/// idle / downloading (с суммарными %/байтами) / done / failed / cancelled.
/// Загрузка идёт в фоновом потоке; тяжёлая механика сеть/диск/проверка — в ModelDownloadEngine.
///
/// Только онлайн: isOnline() честно отказывает в старте, если сети нет.
/// Идемпотентно: уже проверенный на месте пак сразу переходит в .done.
///
/// Калька с Android ModelDownloadManager.
@MainActor
final class ModelDownloadManager: ObservableObject {

    static let shared = ModelDownloadManager()

    private let engine: ModelDownloadEngine
    private let monitor = NWPathMonitor()
    private var currentPath: NWPath?

    @Published private(set) var states: [String: DownloadState] = [:]
    private var cancelFlags: [String: () -> Void] = [:]

    enum DownloadState: Equatable {
        case idle
        case downloading(bytesDone: Int64, bytesTotal: Int64, currentFile: String?)
        case done
        case cancelled
        case failed(String)

        var fraction: Float {
            if case .downloading(let done, let total, _) = self, total > 0 {
                return Float(done) / Float(total)
            }
            return 0
        }

        static func == (lhs: DownloadState, rhs: DownloadState) -> Bool {
            switch (lhs, rhs) {
            case (.idle, .idle): return true
            case (.done, .done): return true
            case (.cancelled, .cancelled): return true
            case (.downloading(let a, let b, let c), .downloading(let x, let y, let z)):
                return a == x && b == y && c == z
            case (.failed(let a), .failed(let b)): return a == b
            default: return false
            }
        }
    }

    init(engine: ModelDownloadEngine = ModelDownloadEngine()) {
        self.engine = engine
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.currentPath = path
            }
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
    }

    /// true, когда есть рабочее подтверждённое сетевое соединение.
    func isOnline() -> Bool {
        guard let path = currentPath else {
            // Монитор ещё не сработал — на всякий случай считаем, что офлайн.
            return false
        }
        return path.status == .satisfied
    }

    func state(for modelId: String) -> DownloadState {
        states[modelId] ?? .idle
    }

    func isDownloading(_ modelId: String) -> Bool {
        if case .downloading = states[modelId] { return true }
        return false
    }

    /// Запускает (или возобновляет) загрузку modelId. Ничего не делает, если уже качается.
    /// Честно отказывает в офлайне или когда для id модели нет спеки загрузки.
    func start(modelId: String) {
        guard let spec = ModelDownloadSpecs.forModelId(modelId) else {
            states[modelId] = .failed("No download source configured for \(modelId)")
            return
        }
        if isDownloading(modelId) { return }
        guard isOnline() else {
            states[modelId] = .failed("No network — download is online only")
            return
        }

        states[modelId] = .downloading(bytesDone: 0, bytesTotal: spec.totalBytes, currentFile: nil)

        var isCancelled = false
        cancelFlags[modelId] = { isCancelled = true }

        let modelDir = resolveModelDir(modelId: modelId)
        let engineRef = engine

        Task.detached(priority: .userInitiated) { [weak self] in
            let result = engineRef.download(
                spec: spec,
                modelDir: modelDir,
                cancelled: { isCancelled }
            ) { progress in
                Task { @MainActor in
                    guard let self else { return }
                    // Не затираем терминальное состояние запоздавшим тиком прогресса после отмены.
                    if case .downloading = self.states[modelId] {
                        self.states[modelId] = .downloading(
                            bytesDone: progress.bytesDone,
                            bytesTotal: progress.bytesTotal,
                            currentFile: progress.currentFile
                        )
                    }
                }
            }
            await MainActor.run { [weak self] in
                guard let self else { return }
                switch result {
                case .success:
                    self.states[modelId] = .done
                case .cancelled:
                    self.states[modelId] = .cancelled
                case .failure(let message):
                    self.states[modelId] = .failed(message)
                }
                self.cancelFlags.removeValue(forKey: modelId)
            }
        }
    }

    /// Кооперативно отменяет идущую загрузку. Недокачанный .part оставляем для возобновления.
    func cancel(modelId: String) {
        cancelFlags[modelId]?()
    }

    /// Возвращает терминальный пак (done/failed/cancelled) обратно в idle.
    func reset(modelId: String) {
        if !isDownloading(modelId) {
            states[modelId] = .idle
        }
    }

    /// Удаляет файлы установленного пака (и оставшиеся .part), освобождая место.
    /// Отказывает, пока идёт загрузка. Возвращает true, когда каталог больше не существует.
    @discardableResult
    func delete(modelId: String) -> Bool {
        if isDownloading(modelId) { return false }
        let dir = resolveModelDir(modelId: modelId)
        let fm = FileManager.default
        try? fm.removeItem(at: dir)
        states[modelId] = .idle
        return !fm.fileExists(atPath: dir.path)
    }

    // MARK: - Private

    private func resolveModelDir(modelId: String) -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let root = support.appendingPathComponent("models")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root.appendingPathComponent(modelId)
    }
}
