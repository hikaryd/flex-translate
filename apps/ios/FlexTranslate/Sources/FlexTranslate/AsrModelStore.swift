import Foundation

// On-device model store for iOS. Resolves where streaming-ASR model files live
// and reports their honest installed state.
//
// Weights are NOT bundled in the app (license/size) — they arrive via first-run
// download or, for the simulator demo, an xcrun simctl / file-copy into the app's
// Documents directory.
//
// Layout: <Application Support>/models/<modelId>/<file>
// (Documents/<modelId>/ is also checked as a secondary location for easy sideloading.)
//
// Mirrors Android's AsrModelStore layout/logic.
@MainActor
final class AsrModelStore {
    static let shared = AsrModelStore()

    private let fm = FileManager.default
    private let modelsDir = "models"

    private init() {}

    // Primary writable models root: Application Support/models/
    func modelsRoot() -> URL {
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let root = support.appendingPathComponent(modelsDir)
        try? fm.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    // Candidate roots checked in order. Application Support first (matches download
    // target), Documents second (easy sideloading / xcrun simctl path).
    private func modelRoots() -> [URL] {
        var roots: [URL] = [modelsRoot()]
        if let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first {
            roots.append(docs.appendingPathComponent(modelsDir))
            // Also check Documents/<modelId> directly for flat sideloading.
            roots.append(docs)
        }
        return roots
    }

    // Directory for the spec: first root that already has the model installed,
    // else the primary writable root (so downloads land in a stable place).
    func modelDir(for spec: AsrModelSpec) -> URL {
        for root in modelRoots() {
            let dir = root.appendingPathComponent(spec.modelId)
            if spec.isInstalled(in: dir) { return dir }
        }
        return modelsRoot().appendingPathComponent(spec.modelId)
    }

    func isInstalled(_ spec: AsrModelSpec) -> Bool {
        spec.isInstalled(in: modelDir(for: spec))
    }

    // Inspect installed files — used for honest UI reporting.
    struct FileStatus {
        let name: String
        let present: Bool
        let sizeBytes: Int64
    }

    struct InstallReport {
        let modelId: String
        let installed: Bool
        let totalSizeBytes: Int64
        let files: [FileStatus]
        var totalSizeMB: Double { Double(totalSizeBytes) / (1024 * 1024) }
    }

    func inspect(_ spec: AsrModelSpec) -> InstallReport {
        let dir = modelDir(for: spec)
        let files = spec.requiredFiles.map { name -> FileStatus in
            let url = dir.appendingPathComponent(name)
            let attrs = try? fm.attributesOfItem(atPath: url.path)
            let size = attrs?[.size] as? Int64 ?? 0
            return FileStatus(name: name, present: size > 0, sizeBytes: size)
        }
        return InstallReport(
            modelId: spec.modelId,
            installed: files.allSatisfy(\.present),
            totalSizeBytes: files.reduce(0) { $0 + $1.sizeBytes },
            files: files
        )
    }
}
