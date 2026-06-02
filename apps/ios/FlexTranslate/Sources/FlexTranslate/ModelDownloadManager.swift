import Foundation
import Network

/// Real in-app model download manager. Acquires a pack's files from their source URLs into
/// the same Application Support/models/<modelId>/ root the AsrModelStore / MtModelStore
/// resolve, so a completed download is immediately visible to the runtime.
///
/// Per pack it exposes an observable DownloadState the Models screen renders:
/// idle / downloading (with aggregate %/bytes) / done / failed / cancelled.
/// Downloads run on a background thread; heavy network/disk/verify mechanics live in ModelDownloadEngine.
///
/// Online-only: isOnline() gates start with an honest refusal when offline.
/// Idempotent: a verified-present pack short-circuits to .done.
///
/// Mirrors Android ModelDownloadManager.
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

    /// True when a usable validated network connection exists.
    func isOnline() -> Bool {
        guard let path = currentPath else {
            // Monitor not yet fired; assume offline to be safe.
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

    /// Start (or resume) the download for modelId. No-op if already downloading.
    /// Refuses honestly when offline or when the model id has no download spec.
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
                    // Don't clobber a terminal state with a late progress tick after cancel.
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

    /// Cooperatively cancel an in-flight download. The partial .part is kept for a later resume.
    func cancel(modelId: String) {
        cancelFlags[modelId]?()
    }

    /// Reset a terminal (done/failed/cancelled) pack back to idle.
    func reset(modelId: String) {
        if !isDownloading(modelId) {
            states[modelId] = .idle
        }
    }

    /// Delete an installed pack's files (and any leftover .parts), freeing storage.
    /// Refuses while a download is in flight. Returns true when the directory no longer exists.
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
