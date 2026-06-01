import Foundation

// Voice-activity-detection contract for the WS2 audio pipeline.
//
// A `Vad` consumes mono Int16 PCM frames (see `AudioFrame`) and emits a
// transition event only when speech starts or stops — never per frame. This
// keeps the protocol cheap to call on the capture thread and lets the UI bind
// to discrete `vad_speech_start` / `vad_speech_end` telemetry events.
protocol Vad {
    // Feed one captured frame. Returns a transition event iff the speech state
    // flipped on this frame, otherwise nil.
    func accept(_ frame: AudioFrame) -> VadEvent?

    // Drop all accumulated state (hangover counters, current state). Called on
    // capture start/stop so a new session never inherits a stale speech flag.
    func reset()
}

// Discrete speech-boundary transition. The associated value is the frame's
// monotonic timestamp (ms) so telemetry can anchor the event in WS6. The names
// map 1:1 to telemetry `event_type` values `vad_speech_start`/`vad_speech_end`.
enum VadEvent: Equatable {
    case speechStart(Int64)
    case speechEnd(Int64)
}

// Current detector state. `silence` is the resting state before any speech and
// after a confirmed end-of-speech; `speech` holds while voice is present.
enum VadState: Equatable, CustomStringConvertible {
    case silence
    case speech

    var description: String {
        switch self {
        case .silence: return "silence"
        case .speech: return "speech"
        }
    }
}
