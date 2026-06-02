import Foundation

// On-device, in-memory bounded ring buffer of TelemetryEvents.
//
// Privacy contract:
// - NO network I/O, ever. Telemetry is strictly local to the device.
// - The only off-memory output is an optional debug JSONL file in the app's
//   Documents directory (debug builds only, never shipped to a server).
// - Fields are limited to the schema: no audio, no transcripts, no free-form
//   PII beyond the schema's typed fields.
//
// Thread safety: all public methods are guarded by a lock.
final class TelemetrySink: @unchecked Sendable {

    // MARK: - Constants

    /// Keep the last 500 events in memory — ~500 * ~300 bytes ≈ 150 KB maximum.
    static let defaultCapacity = 500

    /// Schema-defined event type constants — avoids stringly-typed scatter in callers.
    static let evtSessionStart     = "session_start"
    static let evtCaptureStart     = "capture_start"
    static let evtCaptureStop      = "capture_stop"
    static let evtVadSpeechStart   = "vad_speech_start"
    static let evtVadSpeechEnd     = "vad_speech_end"
    static let evtAsrPartial       = "asr_partial_emitted"
    static let evtAsrFinal         = "asr_final_emitted"
    static let evtMtStart          = "mt_request_started"
    static let evtMtEnd            = "mt_result_emitted"
    static let evtModelLoad        = "model_load"

    /// Mode values (schema enum).
    static let modeOffline = "offline"
    static let modeCloud   = "gemini_batch"

    /// Network state values (schema enum).
    static let netOnline  = "online"
    static let netOffline = "offline"
    static let netUnknown = "unknown"

    /// Device tier values (schema enum).
    static let tierHigh    = "high"
    static let tierMid     = "mid"
    static let tierLow     = "low"
    static let tierUnknown = "unknown"

    // MARK: - Shared instance

    static let shared = TelemetrySink()

    // MARK: - State

    private let capacity: Int
    private let debugJsonlURL: URL?
    private let clock: () -> Int64

    private let lock = NSLock()

    /// Ring buffer backed by an Array; add to tail, drop from head on overflow.
    private var ring: [TelemetryEvent]
    private var head = 0
    private var count = 0

    /// Total events ever accepted (monotonically increasing, never reset).
    private(set) var totalAccepted: Int64 = 0

    /// Total events dropped due to the ring being full.
    private(set) var totalDropped: Int64 = 0

    // MARK: - Init

    /// Create a sink.
    /// - Parameters:
    ///   - capacity: Maximum events retained in memory. Oldest is dropped on overflow.
    ///   - debugJsonlURL: When non-nil (debug builds only), every accepted event is
    ///     appended as a JSONL line to this file. Production callers pass nil.
    ///   - clock: Clock for `TelemetryEvent.monotonicTsMs`. Overridable in tests.
    init(
        capacity: Int = TelemetrySink.defaultCapacity,
        debugJsonlURL: URL? = nil,
        clock: @escaping @Sendable () -> Int64 = {
            Int64(ProcessInfo.processInfo.systemUptime * 1000)
        }
    ) {
        precondition(capacity > 0, "capacity must be positive, was \(capacity)")
        self.capacity = capacity
        self.debugJsonlURL = debugJsonlURL
        self.clock = clock
        self.ring = [TelemetryEvent](repeating: TelemetryEvent.placeholder, count: capacity)
    }

    // MARK: - Accept / Emit

    /// Accept `event` into the ring, stamping `monotonicTsMs` from `clock` if the
    /// event carries a zero timestamp.
    func accept(_ event: TelemetryEvent) {
        let stamped: TelemetryEvent
        if event.monotonicTsMs == 0 {
            stamped = event.withTs(clock())
        } else {
            stamped = event
        }

        lock.lock()
        let dropped: Bool
        if count < capacity {
            ring[(head + count) % capacity] = stamped
            count += 1
            dropped = false
        } else {
            // Overwrite the oldest slot (head) and advance head.
            ring[head] = stamped
            head = (head + 1) % capacity
            dropped = true
        }
        if dropped { totalDropped += 1 }
        totalAccepted += 1
        lock.unlock()

        if let url = debugJsonlURL {
            appendJsonl(url: url, event: stamped)
        }
    }

    /// Build and accept an event in one call. `monotonicTsMs` is auto-filled from `clock` when 0.
    func emit(
        sessionId: String,
        eventType: String,
        deviceTier: String,
        deviceModel: String,
        osVersion: String,
        runtimeId: String,
        modelId: String,
        languagePair: String,
        mode: String,
        networkState: String,
        appBuild: String,
        monotonicTsMs: Int64 = 0,
        payload: [String: String] = [:]
    ) {
        let ts = monotonicTsMs != 0 ? monotonicTsMs : clock()
        accept(TelemetryEvent(
            sessionId: sessionId,
            monotonicTsMs: ts,
            eventType: eventType,
            deviceTier: deviceTier,
            deviceModel: deviceModel,
            osVersion: osVersion,
            runtimeId: runtimeId,
            modelId: modelId,
            languagePair: languagePair,
            mode: mode,
            networkState: networkState,
            appBuild: appBuild,
            payload: payload
        ))
    }

    // MARK: - Query

    /// Return a snapshot of the last `n` events, newest last (arrival order).
    func recent(n: Int = 20) -> [TelemetryEvent] {
        lock.lock()
        defer { lock.unlock() }
        guard count > 0 else { return [] }
        let take = min(n, count)
        var result = [TelemetryEvent]()
        result.reserveCapacity(take)
        // Walk from (head + count - take) mod capacity to cover the last `take` entries.
        let startOffset = count - take
        for i in 0..<take {
            result.append(ring[(head + startOffset + i) % capacity])
        }
        return result
    }

    /// Derive p50/p95 of a numeric payload field (e.g. `"latency_ms"`) from recent events
    /// of `eventType`.
    func latencyPercentiles(eventType: String, payloadKey: String = "latency_ms") -> Percentiles {
        lock.lock()
        var samples = [Int64]()
        for i in 0..<count {
            let ev = ring[(head + i) % capacity]
            if ev.eventType == eventType,
               let raw = ev.payload[payloadKey],
               let ms = Int64(raw) {
                samples.append(ms)
            }
        }
        lock.unlock()
        return computePercentiles(samples)
    }

    /// Clear the ring buffer. Used in tests.
    func clear() {
        lock.lock()
        head = 0
        count = 0
        totalAccepted = 0
        totalDropped = 0
        lock.unlock()
    }

    /// Current count of events in the buffer.
    var size: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    // MARK: - Debug JSONL export

    private func appendJsonl(url: URL, event: TelemetryEvent) {
        let line = event.toJsonLine() + "\n"
        guard let data = line.data(using: .utf8) else { return }
        // Silently ignore I/O errors — telemetry must never crash the host.
        do {
            if FileManager.default.fileExists(atPath: url.path) {
                let handle = try FileHandle(forWritingTo: url)
                handle.seekToEndOfFile()
                handle.write(data)
                try handle.close()
            } else {
                try data.write(to: url, options: .atomic)
            }
        } catch {
            // Silent — telemetry must not affect user-facing behaviour.
        }
    }
}

// MARK: - Percentiles

/// p50 and p95 latency in ms, or nil when there are fewer samples than needed.
struct Percentiles {
    let p50Ms: Int64?
    let p95Ms: Int64?
    let sampleCount: Int
}

func computePercentiles(_ samples: [Int64]) -> Percentiles {
    guard !samples.isEmpty else {
        return Percentiles(p50Ms: nil, p95Ms: nil, sampleCount: 0)
    }
    let sorted = samples.sorted()
    let n = sorted.count
    let p50 = sorted[max(0, min(n * 50 / 100, n - 1))]
    let p95 = sorted[max(0, min(n * 95 / 100, n - 1))]
    return Percentiles(p50Ms: p50, p95Ms: p95, sampleCount: n)
}
