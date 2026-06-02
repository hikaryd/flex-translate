import SwiftUI

// Эфир / Live — the primary live-interpreter surface (tab 0).
// Transcript-dominant, with mode/pair/readiness always legible.
struct LiveView: View {
    // Injected shared session (owned by ContentView) so Диагностика observes the
    // same capture/VAD state.
    @ObservedObject var model: LiveSessionModel

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabScaffold(title: "Эфир") {
            statusStrip
            micMeter
            transcriptPanel
            translationField
            captureControl
        }
        .task {
            await model.refreshPermission()
        }
        // Fix 1: stop capture on tab-switch or backgrounding so WS2 can wire AVAudioEngine cleanly.
        .onDisappear {
            model.stopIfNeeded()
        }
        // iOS 16-compatible single-parameter form (the two-parameter
        // onChange(of:initial:_:) is iOS 17+; deployment target is 16.0).
        .onChange(of: scenePhase) { newPhase in
            if newPhase == .background {
                model.stopIfNeeded()
            }
        }
    }

    // 1. Status strip — mode / pair / readiness pills.
    private var statusStrip: some View {
        HStack(spacing: 8) {
            Badge(text: model.cloudActive ? "cloud" : "offline", color: model.cloudActive ? FlexStatus.info : FlexTheme.primary)
            Badge(text: model.languagePair, color: FlexTheme.secondary, monospaced: true)
            readinessBadge
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private var readinessBadge: some View {
        switch model.offlineState {
        case .readyOfflineAsr:
            Badge(text: "микрофон готов", color: FlexStatus.green)
        case let .captureBlocked(reason):
            Badge(text: reason, color: FlexStatus.red)
        case let .missingOfflinePack(packId):
            Badge(text: "нет пакета: \(packId)", color: FlexStatus.amber)
        case .unsupportedOfflineTranslation:
            Badge(text: "облако выключено", color: FlexTheme.mutedText)
        case .cloudDisabled:
            // Default initial state before permission probe — neutral, not a permission error.
            Badge(text: "offline", color: FlexTheme.mutedText)
        }
    }

    // 2. Mic level meter + real VAD indicator. iOS does not compute CaptureStats
    // (peak/rms stay pending), but the energy VAD runs on real captured PCM, so
    // the речь/тишина pill is a genuine A1 signal — never a fabricated level.
    private var micMeter: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Уровень микрофона")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(FlexTheme.mutedText)
                Spacer()
                if model.isCapturing {
                    Badge(
                        text: model.speechActive ? "речь" : "тишина",
                        color: model.speechActive ? FlexTheme.primary : FlexTheme.mutedText
                    )
                } else {
                    Text("idle")
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(FlexTheme.mutedText)
                }
            }
            // No level metering on iOS (no CaptureStats path); empty track, never a fake level.
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(FlexTheme.elevated)
                .frame(height: 10)
            HStack(spacing: 16) {
                StatRow(key: "peak", value: "", pending: true)
                StatRow(key: "rms", value: "", pending: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .profileSurface()
    }

    // 3. Transcript panel — dominant area. Empty in WS1 → A1 placeholder.
    private var transcriptPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Транскрипт")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(FlexTheme.text)
            if model.transcript.isEmpty {
                Text("ASR support пока не заявлен — транскрипт появится после загрузки локальной модели (см. Модели).")
                    .font(.system(size: 13))
                    .foregroundStyle(FlexTheme.mutedText)
                    .frame(maxWidth: .infinity, minHeight: 180, alignment: .center)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 8)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(model.transcript.enumerated()), id: \.offset) { _, event in
                        Text(event.text)
                            .font(.system(size: 15))
                            .italic(!event.isFinal)
                            .foregroundStyle(event.isFinal ? FlexTheme.text : FlexTheme.mutedText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(minHeight: 180, alignment: .top)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel()
    }

    // 4. Translation field — gated/unsupported state is explicit; never fabricated.
    private var translationField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Перевод")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(FlexTheme.mutedText)
            if let reason = model.translation?.unsupportedReason {
                Text(reason)
                    .font(.system(size: 13))
                    .foregroundStyle(FlexStatus.amber)
            } else if let text = model.translation?.text, !text.isEmpty {
                Text(text)
                    .font(.system(size: 15))
                    .foregroundStyle(FlexTheme.text)
            } else {
                Text("перевод появится после загрузки MT-модели (demo, качество не проверено)")
                    .font(.system(size: 13))
                    .foregroundStyle(FlexTheme.mutedText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .profileSurface()
    }

    // 5. Capture control — start/stop with honest disabled state + helper text.
    private var captureControl: some View {
        VStack(spacing: 8) {
            Button {
                model.toggleCapture()
            } label: {
                Text(model.isCapturing ? "Стоп" : "Слушать")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .foregroundStyle(model.isCapturing ? FlexTheme.text : FlexTheme.onAccent)
            .background(model.isCapturing ? FlexTheme.elevated : FlexTheme.primary)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(model.isCapturing ? FlexTheme.primary : Color.clear, lineWidth: 1)
            )
            .disabled(!model.canCapture)
            .opacity(model.canCapture ? 1.0 : 0.5)
            .accessibilityIdentifier("live.captureToggle")

            if let reason = model.captureBlockReason {
                Text(reason)
                    .font(.system(size: 12))
                    .foregroundStyle(FlexStatus.red)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .multilineTextAlignment(.center)
            }

            testAudioPanel
        }
    }

    // A2 demo: test-audio button feeds a known WAV through the real provider.
    // Shows real decoded text or an honest error — never fabricated output.
    @ViewBuilder
    private var testAudioPanel: some View {
        VStack(spacing: 6) {
            Button {
                model.runTestAudio()
            } label: {
                HStack {
                    if model.testAudioRunning {
                        ProgressView().scaleEffect(0.7)
                    }
                    Text(model.testAudioRunning ? "распознаём…" : "▶ тест-аудио (RU offline ASR)")
                        .font(.system(size: 13, weight: .medium))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            .foregroundStyle(FlexTheme.primary)
            .background(FlexTheme.elevated)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .disabled(model.testAudioRunning)
            .accessibilityIdentifier("live.testAudio")

            if let result = model.testAudioResult {
                Text(result)
                    .font(.system(size: 13))
                    .foregroundStyle(result.hasPrefix("⚠") ? FlexStatus.amber : FlexStatus.green)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .multilineTextAlignment(.leading)
                    .padding(.horizontal, 4)
                    .accessibilityIdentifier("live.testAudioResult")
            }
        }
    }
}
