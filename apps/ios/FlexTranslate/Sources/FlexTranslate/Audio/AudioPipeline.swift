import Foundation

// WS2 audio pipeline: the seam between raw capture and the ASR/VAD layer.
//
// Per frame it (1) admits the frame into a bounded drop-oldest ring buffer so a
// slow consumer can never grow memory without bound, (2) runs the injected VAD
// and records the latest state/event, and (3) forwards the frame to the
// injected `AsrProvider`. The ASR provider is the gated A1 placeholder today
// (returns []), so no transcript is fabricated — only real VAD state flows out.
//
// Not thread-safe by design: the capture tap calls `accept` serially on its own
// callback thread, and `LiveSessionModel` hops state updates to @MainActor. The
// pipeline holds no concurrency primitives of its own.
final class AudioPipeline {
    // Outcome of admitting one frame, surfaced so callers/telemetry can react to
    // the VAD transition and to buffer pressure without re-reading state.
    struct FrameOutcome: Equatable {
        let vadEvent: VadEvent?
        let droppedOldest: Bool
    }

    private let vad: Vad
    private let asr: AsrProvider
    private let capacity: Int

    // Drop-oldest ring buffer of recent frames. Bounded at `capacity`; the
    // oldest entry is evicted before a new one is appended when full.
    private var ringBuffer: [AudioFrame] = []
    private var head = 0

    private(set) var vadState: VadState = .silence
    private(set) var latestEvent: VadEvent?

    // Most recent transcript events from the ASR provider (empty at A1).
    private(set) var transcript: [TranscriptEvent] = []

    init(vad: Vad, asr: AsrProvider, capacity: Int = 64) {
        self.vad = vad
        self.asr = asr
        self.capacity = max(1, capacity)
        ringBuffer.reserveCapacity(self.capacity)
    }

    var bufferDepth: Int { ringBuffer.count }
    var speechActive: Bool { vadState == .speech }

    // Admit one captured frame through buffer -> VAD -> ASR. Returns the frame
    // outcome (VAD transition, whether an old frame was dropped).
    @discardableResult
    func accept(_ frame: AudioFrame) -> FrameOutcome {
        let dropped = enqueue(frame)

        let event = vad.accept(frame)
        if let event {
            latestEvent = event
            switch event {
            case .speechStart:
                vadState = .speech
            case .speechEnd:
                vadState = .silence
            }
        }

        let events = asr.accept(frame: frame)
        if !events.isEmpty {
            transcript = events
        }

        return FrameOutcome(vadEvent: event, droppedOldest: dropped)
    }

    // Clear buffer + VAD + ASR for a fresh capture session.
    func reset() {
        ringBuffer.removeAll(keepingCapacity: true)
        head = 0
        vadState = .silence
        latestEvent = nil
        transcript = []
        vad.reset()
        asr.reset()
    }

    // Bounded append. Returns true when the oldest frame was evicted to make
    // room (back-pressure signal). Uses a moving head index so steady-state
    // appends stay O(1) once the buffer is full.
    private func enqueue(_ frame: AudioFrame) -> Bool {
        if ringBuffer.count < capacity {
            ringBuffer.append(frame)
            return false
        }
        ringBuffer[head] = frame
        head = (head + 1) % capacity
        return true
    }
}
