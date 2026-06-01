import Foundation

// Model-free A1 default VAD; real Silero VAD (sherpa-onnx) is the gated A2 swap behind this protocol.
//
// Strategy: compute RMS energy per frame, normalise to 0...1 against full-scale
// Int16, and compare to `energyThreshold`. A simple two-state machine with
// hangover debounce avoids chattering on word gaps and short transients:
//   - silence -> speech requires `minSpeechFrames` consecutive loud frames
//   - speech -> silence requires `minSilenceFrames` consecutive quiet frames
// Only the confirmed transition emits a `VadEvent`.
final class EnergyVad: Vad {
    // RMS (normalised 0...1) above which a frame counts as "loud".
    private let energyThreshold: Float
    // Consecutive loud frames needed to confirm speech onset.
    private let minSpeechFrames: Int
    // Consecutive quiet frames needed to confirm speech offset (hangover).
    private let minSilenceFrames: Int

    private var state: VadState = .silence
    // Counts consecutive frames matching the *opposite* of the current state,
    // i.e. evidence accumulating toward the next transition.
    private var transitionFrames = 0

    // Defaults tuned for 16 kHz / ~1024-sample frames (~64 ms). Conservative
    // threshold; debounce of a few frames covers natural inter-word gaps.
    init(
        energyThreshold: Float = 0.012,
        minSpeechFrames: Int = 2,
        minSilenceFrames: Int = 8
    ) {
        self.energyThreshold = energyThreshold
        self.minSpeechFrames = max(1, minSpeechFrames)
        self.minSilenceFrames = max(1, minSilenceFrames)
    }

    var currentState: VadState { state }

    func accept(_ frame: AudioFrame) -> VadEvent? {
        let loud = Self.rms(of: frame.pcm16) >= energyThreshold

        switch state {
        case .silence:
            guard loud else {
                transitionFrames = 0
                return nil
            }
            transitionFrames += 1
            guard transitionFrames >= minSpeechFrames else { return nil }
            state = .speech
            transitionFrames = 0
            return .speechStart(frame.monotonicTsMs)

        case .speech:
            guard !loud else {
                transitionFrames = 0
                return nil
            }
            transitionFrames += 1
            guard transitionFrames >= minSilenceFrames else { return nil }
            state = .silence
            transitionFrames = 0
            return .speechEnd(frame.monotonicTsMs)
        }
    }

    func reset() {
        state = .silence
        transitionFrames = 0
    }

    // Int16 full-scale magnitude. Using 32768 (not Int16.max=32767) keeps a
    // full-scale negative sample (-32768) within the normalised [-1, 1] range.
    private static let int16FullScale = 32768.0

    // Root-mean-square amplitude normalised against Int16 full scale.
    // Returns 0 for an empty frame (no samples -> treated as silence).
    private static func rms(of samples: [Int16]) -> Float {
        guard !samples.isEmpty else { return 0 }
        var sumSquares: Double = 0
        for sample in samples {
            let normalised = Double(sample) / int16FullScale
            sumSquares += normalised * normalised
        }
        let meanSquare = sumSquares / Double(samples.count)
        return Float(meanSquare.squareRoot())
    }
}
