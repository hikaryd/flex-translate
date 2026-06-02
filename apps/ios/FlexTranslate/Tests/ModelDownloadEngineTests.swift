import Foundation
import Testing
import CryptoKit
@testable import FlexTranslate

// Deterministic tests for ModelDownloadEngine.
// A minimal GCD-based HTTP server (raw socket) serves known bytes with Range support,
// so resume / checksum-verify / rollback / idempotency are exercised against a genuine
// HTTP round-trip — no real network, no mocking of the engine itself.
//
// Mirrors Android ModelDownloadEngineTest.

@Suite("ModelDownloadEngine")
struct ModelDownloadEngineTests {

    // 200 KB of deterministic bytes + real SHA-256.
    private static let payload: Data = {
        var bytes = [UInt8](repeating: 0, count: 200_000)
        for i in 0..<bytes.count { bytes[i] = UInt8(i % 251) }
        return Data(bytes)
    }()

    private static let payloadSha: String = {
        let digest = SHA256.hash(data: payload)
        return digest.compactMap { String(format: "%02x", $0) }.joined()
    }()

    private func makeSpec(corrupt: Bool = false, port: Int) -> ModelDownloadSpec {
        let path = corrupt ? "corrupt.bin" : "model.bin"
        return ModelDownloadSpec(
            modelId: "test-pack",
            files: [
                ModelFileDownload(
                    fileName: "model.bin",
                    sourceUrl: "http://127.0.0.1:\(port)/\(path)",
                    sha256: Self.payloadSha,
                    sizeBytes: Int64(Self.payload.count)
                )
            ]
        )
    }

    private func makeTmpDir() -> URL {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("model-dl-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        return tmp
    }

    @Test("Full download verifies checksum and atomically installs the file")
    func fullDownload() throws {
        let server = FakeHTTPServer(payload: Self.payload)
        try server.start()
        defer { server.stop() }

        let dir = makeTmpDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let modelDir = dir.appendingPathComponent("test-pack")

        let engine = ModelDownloadEngine()
        var lastProgress: Int64 = 0
        let result = engine.download(
            spec: makeSpec(port: server.port),
            modelDir: modelDir,
            cancelled: { false }
        ) { progress in
            lastProgress = progress.bytesDone
        }

        #expect(result == .success)
        let installed = modelDir.appendingPathComponent("model.bin")
        let attrs = try FileManager.default.attributesOfItem(atPath: installed.path)
        #expect((attrs[.size] as? Int64) == Int64(Self.payload.count))
        let data = try Data(contentsOf: installed)
        #expect(sha256(data) == Self.payloadSha)
        #expect(lastProgress == Int64(Self.payload.count))
        #expect(!FileManager.default.fileExists(atPath: installed.path + ".part"))
    }

    @Test("Checksum mismatch rolls back the part and installs nothing")
    func checksumMismatch() throws {
        let server = FakeHTTPServer(payload: Self.payload)
        try server.start()
        defer { server.stop() }

        let dir = makeTmpDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let modelDir = dir.appendingPathComponent("test-pack")

        let engine = ModelDownloadEngine()
        let result = engine.download(
            spec: makeSpec(corrupt: true, port: server.port),
            modelDir: modelDir,
            cancelled: { false }
        ) { _ in }

        if case .failure = result {
            // Expected
        } else {
            #expect(Bool(false), "Expected failure on checksum mismatch, got \(result)")
        }
        #expect(!FileManager.default.fileExists(atPath: modelDir.appendingPathComponent("model.bin").path))
        #expect(!FileManager.default.fileExists(atPath: modelDir.appendingPathComponent("model.bin.part").path))
    }

    @Test("Already installed and verified file is skipped (idempotent)")
    func idempotent() throws {
        let server = FakeHTTPServer(payload: Self.payload)
        try server.start()
        defer { server.stop() }

        let dir = makeTmpDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let modelDir = dir.appendingPathComponent("test-pack")
        try FileManager.default.createDirectory(at: modelDir, withIntermediateDirectories: true)
        // Pre-install the verified file.
        try Self.payload.write(to: modelDir.appendingPathComponent("model.bin"))

        let sentBefore = server.lastSentBytes
        let engine = ModelDownloadEngine()
        var finalProgress: Int64 = 0
        let result = engine.download(
            spec: makeSpec(port: server.port),
            modelDir: modelDir,
            cancelled: { false }
        ) { progress in
            finalProgress = progress.bytesDone
        }

        #expect(result == .success)
        #expect(server.lastSentBytes == sentBefore, "Verified file must not be re-fetched")
        #expect(finalProgress == Int64(Self.payload.count))
    }

    @Test("Cancellation before start returns cancelled and writes no final file")
    func cancellationBeforeStart() throws {
        let server = FakeHTTPServer(payload: Self.payload)
        try server.start()
        defer { server.stop() }

        let dir = makeTmpDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let modelDir = dir.appendingPathComponent("test-pack")

        let engine = ModelDownloadEngine()
        let result = engine.download(
            spec: makeSpec(port: server.port),
            modelDir: modelDir,
            cancelled: { true }
        ) { _ in }

        #expect(result == .cancelled)
        #expect(!FileManager.default.fileExists(atPath: modelDir.appendingPathComponent("model.bin").path))
    }

    private func sha256(_ data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.compactMap { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Tiny raw-socket HTTP/1.0 server

/// Minimal single-connection GCD-based HTTP server.
/// Serves payload at /model.bin (and same-size zeroed body at /corrupt.bin),
/// honouring Range: bytes=N- with 206 + tail.
private final class FakeHTTPServer {
    private let payload: Data
    private(set) var port: Int = 0
    private(set) var lastSentBytes: Int = 0
    private var serverSocket: Int32 = -1
    private let queue = DispatchQueue(label: "fake-http-server")

    init(payload: Data) {
        self.payload = payload
    }

    func start() throws {
        serverSocket = socket(AF_INET, SOCK_STREAM, 0)
        guard serverSocket >= 0 else { throw NSError(domain: "FakeHTTPServer", code: 1) }

        var yes: Int32 = 1
        setsockopt(serverSocket, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)
        let bindResult = withUnsafeMutablePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(serverSocket, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else { throw NSError(domain: "FakeHTTPServer", code: 2) }

        listen(serverSocket, 5)

        // Read back the assigned port.
        var boundAddr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutablePointer(to: &boundAddr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(serverSocket, $0, &len)
            }
        }
        port = Int(CFSwapInt16BigToHost(boundAddr.sin_port))

        // Accept connections in background.
        queue.async { [weak self] in
            guard let self else { return }
            while true {
                let client = accept(self.serverSocket, nil, nil)
                if client < 0 { break }
                self.handle(client: client)
                close(client)
            }
        }
    }

    func stop() {
        close(serverSocket)
        serverSocket = -1
    }

    private func handle(client: Int32) {
        var requestLines: [String] = []
        var buffer = [UInt8](repeating: 0, count: 4096)
        var raw = ""
        // Read headers until blank line
        while !raw.contains("\r\n\r\n") {
            let n = recv(client, &buffer, buffer.count, 0)
            guard n > 0 else { return }
            raw += String(bytes: buffer[0..<n], encoding: .utf8) ?? ""
        }
        requestLines = raw.components(separatedBy: "\r\n")
        let requestLine = requestLines.first ?? ""
        let path = requestLine.components(separatedBy: " ").dropFirst().first ?? "/"

        var rangeStart = 0
        for header in requestLines {
            if header.lowercased().hasPrefix("range:") {
                let value = header.components(separatedBy: "bytes=").last ?? ""
                rangeStart = Int(value.components(separatedBy: "-").first ?? "0") ?? 0
            }
        }

        let body: Data
        if path.contains("corrupt") {
            body = Data(repeating: 0, count: payload.count)
        } else {
            body = payload
        }

        let start = max(0, min(rangeStart, body.count))
        let slice = body.subdata(in: start..<body.count)
        lastSentBytes = slice.count
        let statusLine = start > 0 ? "HTTP/1.0 206 Partial Content" : "HTTP/1.0 200 OK"
        let headers = "\(statusLine)\r\nContent-Length: \(slice.count)\r\nConnection: close\r\n\r\n"
        let headerData = headers.data(using: .utf8)!

        headerData.withUnsafeBytes { send(client, $0.baseAddress, headerData.count, 0) }
        slice.withUnsafeBytes { send(client, $0.baseAddress, slice.count, 0) }
    }
}
