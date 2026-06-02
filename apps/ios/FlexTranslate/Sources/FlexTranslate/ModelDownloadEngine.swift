import Foundation
import CryptoKit

/// Pure, UI-free download engine for one model pack. Owns the real network and disk mechanics
/// so they can be unit-tested deterministically against a local HTTP server:
///
/// - **Resume**: a partial `<file>.part` is continued with an HTTP `Range: bytes=<have>-` request;
///   a server honouring it (206) appends, otherwise (200) the engine restarts that file cleanly.
/// - **Atomic install**: bytes land in `<file>.part`, are SHA-256 verified, then moved to the
///   final name only on a verified match — a reader never sees a half-written or corrupt file.
/// - **Rollback**: on size/checksum mismatch the `.part` is deleted so a retry starts clean.
/// - **Idempotent**: a file already present, correctly sized AND checksum-verified is skipped.
/// - **Cancellable**: cancelled() is polled before each file; cancel leaves the `.part` in place.
///
/// Mirrors Android ModelDownloadEngine.
final class ModelDownloadEngine: @unchecked Sendable {

    static let partSuffix = ".part"
    private let connectTimeoutSeconds: Double
    private let readTimeoutSeconds: Double

    init(connectTimeoutSeconds: Double = 15, readTimeoutSeconds: Double = 30) {
        self.connectTimeoutSeconds = connectTimeoutSeconds
        self.readTimeoutSeconds = readTimeoutSeconds
    }

    /// Aggregate progress across the pack's files.
    struct Progress: Sendable {
        let bytesDone: Int64
        let bytesTotal: Int64
        let currentFile: String?

        var fraction: Float {
            guard bytesTotal > 0 else { return 0 }
            return Float(bytesDone) / Float(bytesTotal)
        }
    }

    /// Terminal outcome of a pack download.
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

    /// Download every file in spec into modelDir.
    /// cancelled() is checked cooperatively before each file.
    /// Never throws; network/IO/verify failures become .failure.
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

        // A stale .part larger than the expected size is corrupt — drop it.
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

        // Synchronous download via semaphore — engine runs on a background thread.
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
            // Server ignored Range — restart clean.
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

        // SHA-256 verify.
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

        // Atomic rename.
        try? fm.removeItem(at: target)
        do {
            try fm.moveItem(at: partURL, to: target)
        } catch {
            try? fm.removeItem(at: partURL)
            return .failed("Could not rename \(file.fileName): \(error.localizedDescription)")
        }
        return .done
    }

    /// A file counts as installed only when present, correctly sized, and checksum-verified.
    private func isInstalledAndVerified(target: URL, file: ModelFileDownload) -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: target.path),
              let size = attrs[.size] as? Int64, size == file.sizeBytes else { return false }
        guard let data = try? Data(contentsOf: target) else { return false }
        let digest = SHA256.hash(data: data)
        let actual = digest.compactMap { String(format: "%02x", $0) }.joined()
        return actual.lowercased() == file.sha256.lowercased()
    }
}
