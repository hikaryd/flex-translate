import Foundation

// Ограниченный по размеру ring buffer событий телеметрии — целиком в памяти,
// на устройстве.
//
// Контракт приватности:
// - Сети НЕТ от слова совсем. Телеметрия живёт строго на устройстве.
// - Единственный выход за пределы памяти — опциональный debug-файл JSONL в
//   Documents приложения (только debug-сборки, на сервер не уходит никогда).
// - Поля ограничены схемой: ни аудио, ни транскриптов, никакой свободной PII
//   сверх типизированных полей схемы.
//
// Потокобезопасность: все публичные методы под локом.
final class TelemetrySink: @unchecked Sendable {

    // MARK: - Constants

    /// Держим последние 500 событий — это максимум ~500 * ~300 байт ≈ 150 КБ.
    static let defaultCapacity = 500

    /// Типы событий из схемы — чтобы не рассыпать строковые литералы по вызовам.
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

    /// Значения mode (enum из схемы).
    static let modeOffline = "offline"
    static let modeCloud   = "gemini_batch"

    /// Значения network state (enum из схемы).
    static let netOnline  = "online"
    static let netOffline = "offline"
    static let netUnknown = "unknown"

    /// Значения device tier (enum из схемы).
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

    /// Ring buffer на массиве: добавляем в хвост, при переполнении выкидываем с головы.
    private var ring: [TelemetryEvent]
    private var head = 0
    private var count = 0

    /// Сколько событий приняли за всё время (только растёт, не сбрасывается).
    private(set) var totalAccepted: Int64 = 0

    /// Сколько событий потеряли из-за переполнения буфера.
    private(set) var totalDropped: Int64 = 0

    // MARK: - Init

    /// Создаёт sink.
    /// - Parameters:
    ///   - capacity: сколько событий держим в памяти. При переполнении выкидываем самое старое.
    ///   - debugJsonlURL: если не nil (только debug-сборки) — каждое принятое событие
    ///     дописывается строкой JSONL в этот файл. В проде передаём nil.
    ///   - clock: источник времени для `TelemetryEvent.monotonicTsMs`. В тестах подменяемый.
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

    /// Кладёт `event` в буфер. Если у события нулевой timestamp — проставляет
    /// `monotonicTsMs` из `clock`.
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
            // Перезаписываем самый старый слот (head) и двигаем head вперёд.
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

    /// Собрать и принять событие за один вызов. При `monotonicTsMs == 0` время берётся из `clock`.
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

    /// Снимок последних `n` событий, новое — в конце (в порядке поступления).
    func recent(n: Int = 20) -> [TelemetryEvent] {
        lock.lock()
        defer { lock.unlock() }
        guard count > 0 else { return [] }
        let take = min(n, count)
        var result = [TelemetryEvent]()
        result.reserveCapacity(take)
        // Идём от (head + count - take) mod capacity, чтобы захватить последние `take` штук.
        let startOffset = count - take
        for i in 0..<take {
            result.append(ring[(head + startOffset + i) % capacity])
        }
        return result
    }

    /// Считает p50/p95 по числовому полю payload (например `"latency_ms"`) среди
    /// недавних событий типа `eventType`.
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

    /// Очистить буфер. Нужно для тестов.
    func clear() {
        lock.lock()
        head = 0
        count = 0
        totalAccepted = 0
        totalDropped = 0
        lock.unlock()
    }

    /// Сколько событий сейчас в буфере.
    var size: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    // MARK: - Debug JSONL export

    private func appendJsonl(url: URL, event: TelemetryEvent) {
        let line = event.toJsonLine() + "\n"
        guard let data = line.data(using: .utf8) else { return }
        // Ошибки I/O молча глотаем — телеметрия не имеет права ронять приложение.
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
            // Тихо — телеметрия не должна влиять на то, что видит пользователь.
        }
    }
}

// MARK: - Percentiles

/// Задержки p50 и p95 в мс; nil, когда сэмплов меньше, чем нужно.
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
