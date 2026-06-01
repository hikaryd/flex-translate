@preconcurrency import AVFoundation
import Foundation

// WS2 microphone capture for the offline ASR pipeline.
//
// The input node's hardware format is typically Float32 at the device sample
// rate (e.g. 48 kHz). The pipeline downstream expects mono Int16 @ 16 kHz, so
// every tap buffer is run through an `AVAudioConverter` and emitted as a
// real-PCM `AudioFrame`. Earlier WS1 code dropped the PCM (emitted empty
// samples); this restores it.
//
// Concurrency (Swift 6 strict): the controller's lifecycle (start/stop/state)
// is `@MainActor`-isolated because it is driven entirely from the main-actor
// `LiveSessionModel`. The render-thread tap callback must NOT touch main-actor
// state, so the conversion context (converter + target format) is built once at
// `start()` time and captured *by value* into the `@Sendable` tap closure —
// the closure never captures `self`. `@preconcurrency import AVFoundation`
// downgrades the framework's pre-Sendable `AVAudioPCMBuffer`/`AVAudioConverter`
// interop diagnostics, which is the documented bridging path.
//
// NOTE: this conversion path compiles and is unit-validated for the pure ring
// buffer/VAD layer, but the live AVAudioEngine capture itself still needs
// on-device validation before it is trusted for a real recognizer (later phase).
@MainActor
final class AudioCaptureController {
    private let engine = AVAudioEngine()

    // Target downstream format: mono Int16 @ 16 kHz, interleaved.
    private let targetSampleRate: Double
    private var isRunning = false

    init(targetSampleRate: Double = 16_000) {
        self.targetSampleRate = targetSampleRate
    }

    // Permission probing is pure (only AVCaptureDevice statics), so it stays
    // `nonisolated`: it can be awaited without sending main-actor state across
    // an isolation boundary.
    nonisolated func permissionState() async -> OfflineFirstState {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            return .readyOfflineAsr
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .audio)
            return granted ? .readyOfflineAsr : .captureBlocked(reason: "Microphone permission is required for offline ASR")
        default:
            return .captureBlocked(reason: "Microphone permission is required for offline ASR")
        }
    }

    var sampleRateHz: Int { Int(targetSampleRate) }

    // Start the engine and emit converted mono Int16 @ 16 kHz frames. Idempotent:
    // a second call while running is a no-op. Throws on engine start failure so
    // the caller can surface a capture-blocked state instead of failing silently.
    func start(onFrame: @escaping @Sendable (AudioFrame) -> Void) throws {
        guard !isRunning else { return }
        installTap(onFrame: onFrame)
        try engine.start()
        isRunning = true
    }

    // Stop the engine and tear down the tap so the next start() rebuilds the
    // conversion context against the then-current hardware format.
    func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRunning = false
    }

    // Installs the capture tap. The conversion context is built once here (on the
    // main actor) and captured by value into the render-thread closure, so the
    // closure never reaches back into `self`. Defensive throughout: no
    // force-unwraps; any setup or conversion failure simply skips that buffer.
    private func installTap(onFrame: @escaping @Sendable (AudioFrame) -> Void) {
        let input = engine.inputNode
        let inputFormat = input.inputFormat(forBus: 0)
        let context = PCMConversionContext(inputFormat: inputFormat, targetSampleRate: targetSampleRate)

        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { buffer, _ in
            let timestamp = Int64(ProcessInfo.processInfo.systemUptime * 1000)
            guard let context, let samples = context.convertToInt16(buffer), !samples.isEmpty else {
                return
            }
            onFrame(AudioFrame(pcm16: samples, sampleRateHz: context.targetSampleRateHz, monotonicTsMs: timestamp))
        }
    }
}

// Immutable, value-captured conversion context for the render-thread tap. Holds
// the converter and target format built once at start time; constructed on the
// main actor and used on the audio thread, but never shared mutably — so it is
// safe to capture in a `@Sendable` closure (AVFoundation's own non-Sendable
// types are bridged via `@preconcurrency`).
// One-shot "input already fed" flag for the AVAudioConverter input block.
// `@unchecked Sendable` is accurate here: the converter invokes its input block
// synchronously on the calling thread for the duration of `convert(to:error:)`,
// so this reference is never touched from two threads at once — but the block's
// `@Sendable` signature still requires its captures to be Sendable.
private final class OneShotFlag: @unchecked Sendable {
    var consumed = false
}

private struct PCMConversionContext {
    let converter: AVAudioConverter
    let inputFormat: AVAudioFormat
    let targetFormat: AVAudioFormat
    let targetSampleRateHz: Int

    // Returns nil if either the mono Int16 @ 16 kHz target format or the
    // converter fails to construct — no force-unwrap on the failable initializers.
    init?(inputFormat: AVAudioFormat, targetSampleRate: Double) {
        guard let target = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: targetSampleRate,
            channels: 1,
            interleaved: true
        ) else {
            return nil
        }
        guard let builtConverter = AVAudioConverter(from: inputFormat, to: target) else {
            return nil
        }
        self.converter = builtConverter
        self.inputFormat = inputFormat
        self.targetFormat = target
        self.targetSampleRateHz = Int(targetSampleRate)
    }

    // Convert one hardware buffer (Float32 @ device rate) to mono Int16 @ 16 kHz
    // and pull the samples out of the converted buffer's int16ChannelData.
    // Returns nil on any failure rather than crashing.
    func convertToInt16(_ buffer: AVAudioPCMBuffer) -> [Int16]? {
        // Size the output buffer by the sample-rate ratio (plus headroom).
        let ratio = targetFormat.sampleRate / inputFormat.sampleRate
        let estimatedFrames = Double(buffer.frameLength) * ratio
        let capacity = AVAudioFrameCount(estimatedFrames.rounded(.up)) + 1
        guard capacity > 0,
              let outputBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
            return nil
        }

        // The `AVAudioConverterInputBlock` is `@Sendable`, so it cannot capture a
        // mutable `var` (Swift 6 strict concurrency). The one-shot "already fed"
        // flag lives in a reference box instead — the block is invoked
        // synchronously on this thread, so a plain class without locking is safe.
        let fed = OneShotFlag()
        var conversionError: NSError?
        let status = converter.convert(to: outputBuffer, error: &conversionError) { _, inputStatus in
            // Feed the source buffer exactly once; signal end-of-stream after.
            if fed.consumed {
                inputStatus.pointee = .noDataNow
                return nil
            }
            fed.consumed = true
            inputStatus.pointee = .haveData
            return buffer
        }

        guard status != .error, conversionError == nil else { return nil }

        let frameCount = Int(outputBuffer.frameLength)
        guard frameCount > 0, let channelData = outputBuffer.int16ChannelData else { return nil }

        // Target is mono interleaved, so channel 0 holds all samples.
        let pointer = channelData[0]
        return Array(UnsafeBufferPointer(start: pointer, count: frameCount))
    }
}
