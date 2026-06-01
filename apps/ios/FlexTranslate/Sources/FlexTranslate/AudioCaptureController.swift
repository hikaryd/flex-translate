import AVFoundation
import Foundation

// WS2 microphone capture for the offline ASR pipeline.
//
// The input node's hardware format is typically Float32 at the device sample
// rate (e.g. 48 kHz). The pipeline downstream expects mono Int16 @ 16 kHz, so
// every tap buffer is run through an `AVAudioConverter` and emitted as a
// real-PCM `AudioFrame`. Earlier WS1 code dropped the PCM (emitted empty
// samples); this restores it.
//
// IMPORTANT (uncompiled): this conversion path has NOT been compiled or run —
// the iOS build is blocked until `sudo xcodebuild -license` is accepted. The
// AVAudioConverter / AVAudioFormat usage below is written from the documented
// API surface and MUST be device-validated in WS3 before it is trusted.
final class AudioCaptureController {
    private let engine = AVAudioEngine()

    // Target downstream format: mono Int16 @ 16 kHz, interleaved.
    private let targetSampleRate: Double

    // Lazily built when the first tap buffer arrives, because the converter
    // needs the input node's real hardware format (known only at start time).
    private var converter: AVAudioConverter?
    private var targetFormat: AVAudioFormat?
    private var isRunning = false

    init(targetSampleRate: Double = 16_000) {
        self.targetSampleRate = targetSampleRate
    }

    func permissionState() async -> OfflineFirstState {
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

    // Stop the engine and tear down the tap + converter so the next start()
    // rebuilds against the then-current hardware format.
    func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        converter = nil
        targetFormat = nil
        isRunning = false
    }

    // Installs the capture tap. Each callback converts the hardware buffer to the
    // target format and emits a real-PCM AudioFrame. Defensive throughout: no
    // force-unwraps; any setup or conversion failure simply skips that buffer.
    private func installTap(onFrame: @escaping @Sendable (AudioFrame) -> Void) {
        let input = engine.inputNode
        let inputFormat = input.inputFormat(forBus: 0)

        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            guard let self else { return }
            let timestamp = Int64(ProcessInfo.processInfo.systemUptime * 1000)
            guard let samples = self.convertToInt16(buffer, inputFormat: inputFormat), !samples.isEmpty else {
                return
            }
            let frame = AudioFrame(
                pcm16: samples,
                sampleRateHz: Int(self.targetSampleRate),
                monotonicTsMs: timestamp
            )
            onFrame(frame)
        }
    }

    // Convert one hardware buffer (Float32 @ device rate) to mono Int16 @ 16 kHz
    // and pull the samples out of the converted buffer's int16ChannelData.
    // Returns nil on any failure rather than crashing.
    private func convertToInt16(_ buffer: AVAudioPCMBuffer, inputFormat: AVAudioFormat) -> [Int16]? {
        guard let converter = makeConverter(for: inputFormat),
              let target = targetFormat else {
            return nil
        }

        // Size the output buffer by the sample-rate ratio (plus headroom).
        let ratio = target.sampleRate / inputFormat.sampleRate
        let estimatedFrames = Double(buffer.frameLength) * ratio
        let capacity = AVAudioFrameCount(estimatedFrames.rounded(.up)) + 1
        guard capacity > 0,
              let outputBuffer = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else {
            return nil
        }

        var fedInput = false
        var conversionError: NSError?
        let status = converter.convert(to: outputBuffer, error: &conversionError) { _, inputStatus in
            // Feed the source buffer exactly once; signal end-of-stream after.
            if fedInput {
                inputStatus.pointee = .noDataNow
                return nil
            }
            fedInput = true
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

    // Build (once) an AVAudioConverter from the hardware format to the mono
    // Int16 @ 16 kHz target. Returns nil if either format/converter fails to
    // construct — no force-unwrap on the optional AVAudioFormat initializer.
    private func makeConverter(for inputFormat: AVAudioFormat) -> AVAudioConverter? {
        if let converter { return converter }

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

        targetFormat = target
        converter = builtConverter
        return builtConverter
    }
}
