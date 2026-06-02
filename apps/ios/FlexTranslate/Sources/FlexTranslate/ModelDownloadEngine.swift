import Foundation
import CryptoKit

/// Чистый, без UI движок загрузки одного пака модели. Держит реальную работу с сетью и диском,
/// чтобы её можно было детерминированно гонять в юнит-тестах против локального HTTP-сервера:
///
/// - **Докачка**: недокачанный `<file>.part` продолжаем запросом `Range: bytes=<have>-`;
///   сервер, который его уважает (206), дописывает, иначе (200) движок начинает файл заново.
/// - **Атомарная установка**: байты падают в `<file>.part`, проверяются по SHA-256 и только при
///   совпадении переименовываются в финал — читатель никогда не увидит полуфайл или мусор.
/// - **Откат**: при несовпадении размера/контрольной суммы `.part` удаляется, чтобы ретрай начал с чистого.
/// - **Идемпотентность**: уже лежащий файл с верным размером И сошедшейся суммой пропускаем.
/// - **Отмена**: cancelled() опрашивается перед каждым файлом; отмена оставляет `.part` на месте.
///
/// Зеркалит Android ModelDownloadEngine.
final class ModelDownloadEngine: @unchecked Sendable {

    static let partSuffix = ".part"
    private let connectTimeoutSeconds: Double
    private let readTimeoutSeconds: Double

    init(connectTimeoutSeconds: Double = 15, readTimeoutSeconds: Double = 30) {
        self.connectTimeoutSeconds = connectTimeoutSeconds
        self.readTimeoutSeconds = readTimeoutSeconds
    }

    /// Суммарный прогресс по всем файлам пака.
    struct Progress: Sendable {
        let bytesDone: Int64
        let bytesTotal: Int64
        let currentFile: String?

        var fraction: Float {
            guard bytesTotal > 0 else { return 0 }
            return Float(bytesDone) / Float(bytesTotal)
        }
    }

    /// Финальный исход загрузки пака.
    enum Result: Sendable, Equatable {
        case success
        case cancelled
        case failure(String)
    }

    private enum FileResult {
        case done
        case cancelled
        case failed(String)
    }

    /// Качает все файлы из spec в modelDir.
    /// cancelled() кооперативно проверяется перед каждым файлом.
    /// Не бросает; сбои сети/IO/проверки превращаются в .failure.
    func download(
        spec: ModelDownloadSpec,
        modelDir: URL,
        cancelled: @escaping () -> Bool,
        onProgress: @escaping (Progress) -> Void
    ) -> Result {
        let fm = FileManager.default
        if !fm.fileExists(atPath: modelDir.path) {
            do {
                try fm.createDirectory(at: modelDir, withIntermediateDirectories: true)
            } catch {
                return .failure("Could not create model dir: \(error.localizedDescription)")
            }
        }
        let total = spec.totalBytes
        var completedBytes: Int64 = spec.files.reduce(0) { acc, file in
            let target = modelDir.appendingPathComponent(file.fileName)
            return acc + (isInstalledAndVerified(target: target, file: file) ? file.sizeBytes : 0)
        }
        onProgress(Progress(bytesDone: completedBytes, bytesTotal: total, currentFile: nil))

        for file in spec.files {
            if cancelled() { return .cancelled }
            let target = modelDir.appendingPathComponent(file.fileName)
            if isInstalledAndVerified(target: target, file: file) { continue }

            let baseBytes = completedBytes
            let fileResult = downloadFile(file: file, target: target, cancelled: cancelled) { fileBytes in
                onProgress(Progress(bytesDone: baseBytes + fileBytes, bytesTotal: total, currentFile: file.fileName))
            }
            switch fileResult {
            case .done:
                completedBytes = baseBytes + file.sizeBytes
            case .cancelled:
                return .cancelled
            case .failed(let message):
                return .failure(message)
            }
            onProgress(Progress(bytesDone: completedBytes, bytesTotal: total, currentFile: nil))
        }
        return .success
    }

    private func downloadFile(
        file: ModelFileDownload,
        target: URL,
        cancelled: @escaping () -> Bool,
        onFileBytes: @escaping (Int64) -> Void
    ) -> FileResult {
        let fm = FileManager.default
        let partURL = URL(fileURLWithPath: target.path + Self.partSuffix)

        // Залежавшийся .part больше ожидаемого размера — битый, выкидываем.
        if let attrs = try? fm.attributesOfItem(atPath: partURL.path),
           let size = attrs[.size] as? Int64, size > file.sizeBytes {
            try? fm.removeItem(at: partURL)
        }

        let existing: Int64
        if fm.fileExists(atPath: partURL.path),
           let attrs = try? fm.attributesOfItem(atPath: partURL.path),
           let size = attrs[.size] as? Int64 {
            existing = size
        } else {
            existing = 0
        }

        guard let url = URL(string: file.sourceUrl) else {
            return .failed("Invalid URL: \(file.sourceUrl)")
        }

        var request = URLRequest(url: url, timeoutInterval: readTimeoutSeconds)
        request.httpMethod = "GET"
        if existing > 0 {
            request.setValue("bytes=\(existing)-", forHTTPHeaderField: "Range")
        }

        // Синхронная загрузка через семафор — движок и так крутится в фоновом потоке.
        let semaphore = DispatchSemaphore(value: 0)
        var responseData: Data?
        var httpStatus: Int = 0
        var networkErrorMsg: String?

        let sessionConfig = URLSessionConfiguration.default
        sessionConfig.timeoutIntervalForRequest = readTimeoutSeconds
        sessionConfig.timeoutIntervalForResource = 3_600
        let session = URLSession(configuration: sessionConfig)

        session.dataTask(with: request) { data, response, error in
            if let error = error {
                networkErrorMsg = error.localizedDescription
            } else if let http = response as? HTTPURLResponse {
                httpStatus = http.statusCode
                responseData = data
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()

        if let msg = networkErrorMsg {
            return .failed("Network error for \(file.fileName): \(msg)")
        }

        let resuming = existing > 0 && httpStatus == 206
        let startFrom: Int64 = resuming ? existing : 0

        if !resuming && existing > 0 {
            // Сервер проигнорировал Range — начинаем файл заново.
            try? fm.removeItem(at: partURL)
        }

        if httpStatus != 200 && httpStatus != 206 {
            return .failed("HTTP \(httpStatus) for \(file.fileName)")
        }

        guard let data = responseData else {
            return .failed("No data received for \(file.fileName)")
        }

        if cancelled() { return .cancelled }

        return streamToPart(
            data: data,
            partURL: partURL,
            target: target,
            file: file,
            startFrom: startFrom,
            onFileBytes: onFileBytes
        )
    }

    private func streamToPart(
        data: Data,
        partURL: URL,
        target: URL,
        file: ModelFileDownload,
        startFrom: Int64,
        onFileBytes: @escaping (Int64) -> Void
    ) -> FileResult {
        let fm = FileManager.default
        let append = startFrom > 0 && fm.fileExists(atPath: partURL.path)

        do {
            if append {
                let handle = try FileHandle(forWritingTo: partURL)
                handle.seekToEndOfFile()
                handle.write(data)
                try handle.close()
            } else {
                try data.write(to: partURL, options: .atomic)
            }
        } catch {
            try? fm.removeItem(at: partURL)
            return .failed("Write error for \(file.fileName): \(error.localizedDescription)")
        }

        guard let attrs = try? fm.attributesOfItem(atPath: partURL.path),
              let written = attrs[.size] as? Int64 else {
            try? fm.removeItem(at: partURL)
            return .failed("Could not stat part file for \(file.fileName)")
        }

        onFileBytes(written)

        if written != file.sizeBytes {
            try? fm.removeItem(at: partURL)
            return .failed("Size mismatch for \(file.fileName) (\(written) != \(file.sizeBytes))")
        }

        // Проверка по SHA-256.
        guard let partData = try? Data(contentsOf: partURL) else {
            try? fm.removeItem(at: partURL)
            return .failed("Could not read part for SHA-256 verify: \(file.fileName)")
        }
        let digest = SHA256.hash(data: partData)
        let actual = digest.compactMap { String(format: "%02x", $0) }.joined()
        guard actual.lowercased() == file.sha256.lowercased() else {
            try? fm.removeItem(at: partURL)
            return .failed("Checksum mismatch for \(file.fileName)")
        }

        // Атомарное переименование.
        try? fm.removeItem(at: target)
        do {
            try fm.moveItem(at: partURL, to: target)
        } catch {
            try? fm.removeItem(at: partURL)
            return .failed("Could not rename \(file.fileName): \(error.localizedDescription)")
        }
        return .done
    }

    /// Файл считается установленным, только если он есть, верного размера и прошёл проверку суммы.
    private func isInstalledAndVerified(target: URL, file: ModelFileDownload) -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: target.path),
              let size = attrs[.size] as? Int64, size == file.sizeBytes else { return false }
        guard let data = try? Data(contentsOf: target) else { return false }
        let digest = SHA256.hash(data: data)
        let actual = digest.compactMap { String(format: "%02x", $0) }.joined()
        return actual.lowercased() == file.sha256.lowercased()
    }
}
