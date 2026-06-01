import SwiftUI

// Диагностика / Diagnostics (tab 4, debug-oriented) — operator trust + debugging.
// Every value is real where available, otherwise "—"/pending. No fabricated metrics.
struct DiagnosticsView: View {
    // Shared session (owned by ContentView) — real capture/VAD state from the Эфир tab.
    @ObservedObject var model: LiveSessionModel

    private var osVersion: String {
        ProcessInfo.processInfo.operatingSystemVersionString
    }

    private var appBuild: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String
        let build = info?["CFBundleVersion"] as? String
        switch (version, build) {
        case let (v?, b?): return "\(v) (\(b))"
        case let (v?, nil): return v
        default: return "—"
        }
    }

    var body: some View {
        TabScaffold(title: "Диагностика") {
            captureCard
            pipelineCard
            buildCard
            telemetryCard
        }
    }

    // iOS has no CaptureStats path (peak/rms/frames pending); isCapturing is real.
    private var captureCard: some View {
        SectionCard(title: "Захват аудио") {
            VStack(spacing: 6) {
                StatRow(key: "isCapturing", value: model.isCapturing ? "true" : "false", pending: false)
                StatRow(key: "sampleRateHz", value: "16000", pending: false)
                StatRow(key: "framesRead", value: "", pending: true)
                StatRow(key: "chunksRead", value: "", pending: true)
                StatRow(key: "elapsedMs", value: "", pending: true)
                StatRow(key: "peak", value: "", pending: true)
                StatRow(key: "rms", value: "", pending: true)
                StatRow(key: "lastError", value: "", pending: true)
            }
        }
    }

    // Pipeline: ASR provider id is real; support is honestly "не заявлен"; rest pending.
    private var pipelineCard: some View {
        SectionCard(title: "Конвейер") {
            VStack(spacing: 6) {
                StatRow(key: "vadState", value: model.isCapturing ? model.vadState.description : "", pending: !model.isCapturing)
                StatRow(key: "asrProviderId", value: model.asrProviderId, pending: false)
                StatRow(key: "asr support", value: "не заявлен", pending: false)
                StatRow(key: "bufferDepth", value: model.isCapturing ? String(model.bufferDepth) : "", pending: !model.isCapturing)
                StatRow(key: "latency p95 (WS6)", value: "", pending: true)
            }
        }
    }

    private var buildCard: some View {
        SectionCard(title: "Сборка / устройство") {
            VStack(spacing: 6) {
                StatRow(key: "appBuild", value: appBuild, pending: appBuild == "—")
                StatRow(key: "osVersion", value: osVersion, pending: false)
                StatRow(key: "deviceTier", value: "", pending: true)
                StatRow(key: "runtimeVersions", value: "", pending: true)
            }
        }
    }

    // Telemetry emission lands in WS6 — no events yet, shown honestly as pending.
    private var telemetryCard: some View {
        SectionCard(title: "Телеметрия", subtitle: "Эмиссия событий появится в WS6.") {
            StatRow(key: "последние события", value: "", pending: true)
        }
    }
}
