import Foundation

// On-device MT model store for iOS. Mirrors AsrModelStore but serves MtModelSpec.
//
// Layout: <Application Support>/models/<modelId>/<file>
// Documents/models/<modelId>/ is also checked (secondary, for xcrun simctl sideloading).
//
// Not marked @MainActor — pure filesystem queries, safe from any thread.
final class MtModelStore: @unchecked Sendable {
    static let shared = MtModelStore()

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
    // target), Documents/models/ second, Documents/<modelId>/ third (flat sideloading).
    private func modelRoots(for spec: MtModelSpec) -> [URL] {
        var roots: [URL] = [modelsRoot()]
        if let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first {
            roots.append(docs.appendingPathComponent(modelsDir))
            // Also check Documents/<modelId> directly for flat sideloading via simctl.
            roots.append(docs)
        }
        return roots
    }

    // Directory for the spec: first root that already has the model installed,
    // else the primary writable root so downloads land in a stable place.
    func modelDir(for spec: MtModelSpec) -> URL {
        for root in modelRoots(for: spec) {
            let dir = root.appendingPathComponent(spec.modelId)
            if spec.isInstalled(in: dir) { return dir }
        }
        return modelsRoot().appendingPathComponent(spec.modelId)
    }

    func isInstalled(_ spec: MtModelSpec) -> Bool {
        spec.isInstalled(in: modelDir(for: spec))
    }
}
