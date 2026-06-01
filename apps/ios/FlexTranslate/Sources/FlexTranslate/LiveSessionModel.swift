import Foundation
import SwiftUI

// View model for the Эфир/Live surface.
//
// WS1 scope: drive the honest end-to-end UI without fabricating output.
// - Permission state comes from the real AudioCaptureController.
// - The ASR provider returns [] until a local model loads (WS2/WS3), so the
//   transcript stays empty and the view shows the A1 placeholder.
// - Translation is benchmark-gated, so it surfaces an explicit unsupported reason.
//
// WS2 adds the real audio pipeline: when capturing, frames flow
// capture -> AudioPipeline -> VAD, and `vadState`/`speechActive` are published.
// VAD is real (RMS energy, A1); ASR is still the gated placeholder, so the
// transcript stays empty and no ASR support is claimed.
@MainActor
final class LiveSessionModel: ObservableObject {
    // Phase-0 scope: RU↔EN only.
    @Published private(set) var sourceLanguage = "RU"
    @Published private(set) var targetLanguage = "EN"

    @Published private(set) var offlineState: OfflineFirstState = .cloudDisabled
    @Published private(set) var isCapturing = false

    // Real ASR output only. Stays empty in WS1/WS2 (provider returns []).
    @Published private(set) var transcript: [TranscriptEvent] = []
    @Published private(set) var translation: TranslationResult?

    // Real VAD state, published only while capturing. `silence` is the resting
    // value; `speechActive` is the derived convenience flag for the UI.
    @Published private(set) var vadState: VadState = .silence

    // Cloud is OFF by default — Live shows the offline mode badge until WS5.
    @Published private(set) var cloudActive = false

    // Fix 5: extracted constant so WS2 can replace it with a runtime device-tier probe.
    private let deviceTier = "high" // TODO(WS2): derive from device class

    private let capture: AudioCaptureController
    private let asr: AsrProvider
    private let translator: TranslationProvider
    private let pipeline: AudioPipeline

    init(
        capture: AudioCaptureController = AudioCaptureController(),
        asr: AsrProvider = PlaceholderLocalAsrProvider(),
        translator: TranslationProvider = GatedTranslationProvider(),
        vad: Vad = EnergyVad()
    ) {
        self.capture = capture
        self.asr = asr
        self.translator = translator
        self.pipeline = AudioPipeline(vad: vad, asr: asr)
    }

    var speechActive: Bool { vadState == .speech }
    var bufferDepth: Int { pipeline.bufferDepth }

    var languagePair: String { "\(sourceLanguage) → \(targetLanguage)" }
    var asrProviderId: String { asr.providerId }

    // Resolve the current microphone permission into an OfflineFirstState.
    func refreshPermission() async {
        let state = await capture.permissionState()
        offlineState = state
        translation = translator.translate(
            text: "",
            languagePair: "\(sourceLanguage.lowercased())->\(targetLanguage.lowercased())",
            deviceTier: deviceTier
        )
    }

    // WS2: real capture is wired through the pipeline. Starting capture runs the
    // mic engine; each converted Int16 frame is routed (on the main actor) through
    // AudioPipeline -> VAD, publishing real `vadState`. ASR stays the gated
    // placeholder (returns []), so the transcript is never fabricated.
    func toggleCapture() {
        if isCapturing {
            stopIfNeeded()
        } else {
            guard canCapture else { return }
            asr.reset()
            pipeline.reset()
            transcript = []
            vadState = .silence
            do {
                try capture.start { [weak self] frame in
                    // The capture tap fires off the main thread; hop to the main
                    // actor so the (non-thread-safe) pipeline and @Published state
                    // are only ever touched from one isolation domain.
                    Task { @MainActor in
                        guard let self, self.isCapturing else { return }
                        self.pipeline.accept(frame)
                        self.vadState = self.pipeline.vadState
                    }
                }
                isCapturing = true
            } catch {
                isCapturing = false
                offlineState = .captureBlocked(reason: "Не удалось запустить аудиозахват")
            }
        }
    }

    // Symmetric stop path: stops the mic engine, clears the pipeline, resets VAD.
    // Call sites: toggleCapture(), LiveView .onDisappear, scenePhase → .background.
    func stopIfNeeded() {
        guard isCapturing else { return }
        capture.stop()
        pipeline.reset()
        isCapturing = false
        vadState = .silence
    }

    var canCapture: Bool {
        switch offlineState {
        case .readyOfflineAsr, .cloudDisabled, .unsupportedOfflineTranslation:
            return true
        case .captureBlocked, .missingOfflinePack:
            return false
        }
    }

    // Helper text explaining why capture is unavailable, if it is.
    var captureBlockReason: String? {
        switch offlineState {
        case let .captureBlocked(reason):
            return reason
        case let .missingOfflinePack(packId):
            return "нет пакета: \(packId) — установите его на вкладке «Модели»"
        case .readyOfflineAsr, .cloudDisabled, .unsupportedOfflineTranslation:
            return nil
        }
    }
}
