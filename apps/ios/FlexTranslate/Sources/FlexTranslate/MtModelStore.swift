import Foundation

// Хранилище on-device MT-моделей для iOS. То же, что AsrModelStore, но под MtModelSpec.
//
// Раскладка: <Application Support>/models/<modelId>/<file>
// Documents/models/<modelId>/ тоже проверяем — туда модель кладут через xcrun simctl.
//
// Без @MainActor: тут только чтение с диска, можно дёргать из любого потока.
final class MtModelStore: @unchecked Sendable {
    static let shared = MtModelStore()

    private let fm = FileManager.default
    private let modelsDir = "models"

    private init() {}

    // Основное место, куда можно писать: Application Support/models/
    func modelsRoot() -> URL {
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let root = support.appendingPathComponent(modelsDir)
        try? fm.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    // Каталоги-кандидаты в порядке проверки: сначала Application Support (туда же
    // качаем), потом Documents/models/, потом Documents/<modelId>/ (плоская заливка).
    private func modelRoots(for spec: MtModelSpec) -> [URL] {
        var roots: [URL] = [modelsRoot()]
        if let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first {
            roots.append(docs.appendingPathComponent(modelsDir))
            // Documents/<modelId> напрямую — на случай плоской заливки через simctl.
            roots.append(docs)
        }
        return roots
    }

    // Каталог под spec: первый корень, где модель уже стоит, иначе основной
    // writable-корень — чтобы загрузка всегда попадала в предсказуемое место.
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
