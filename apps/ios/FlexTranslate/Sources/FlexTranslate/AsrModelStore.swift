import Foundation

// On-device хранилище моделей для iOS. Находит, где лежат файлы streaming-ASR,
// и честно отдаёт их статус установки.
//
// Веса в приложение НЕ зашиты (лицензия/размер) — приезжают при первом запуске
// через download, либо для демо на симуляторе копируются (xcrun simctl / file-copy)
// в Documents приложения.
//
// Раскладка: <Application Support>/models/<modelId>/<file>
// (Documents/<modelId>/ тоже проверяем — туда удобно сайдлоадить.)
//
// Повторяет раскладку/логику AsrModelStore из Android-версии.
@MainActor
final class AsrModelStore {
    static let shared = AsrModelStore()

    private let fm = FileManager.default
    private let modelsDir = "models"

    private init() {}

    // Основной writable-корень для моделей: Application Support/models/
    func modelsRoot() -> URL {
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let root = support.appendingPathComponent(modelsDir)
        try? fm.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    // Корни-кандидаты в порядке проверки. Сначала Application Support (туда же качаем),
    // потом Documents (удобный сайдлоад / путь xcrun simctl).
    private func modelRoots() -> [URL] {
        var roots: [URL] = [modelsRoot()]
        if let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first {
            roots.append(docs.appendingPathComponent(modelsDir))
            // И сам Documents/<modelId> — на случай плоского сайдлоада.
            roots.append(docs)
        }
        return roots
    }

    // Папка для spec: первый корень, где модель уже установлена, иначе основной
    // writable-корень (чтобы загрузки всегда падали в одно стабильное место).
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

    // Осмотр установленных файлов — чтобы UI показывал честную картину.
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
