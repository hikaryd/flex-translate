import SwiftUI

// Диагностика / Diagnostics (tab 4, debug-oriented) — operator trust + debugging.
// Every value is real where available, otherwise "—"/pending. No fabricated metrics.
struct DiagnosticsView: View {
    @ObservedObject var model: LiveSessionModel
    @EnvironmentObject private var appStrings: AppStrings

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
        TabScaffold(title: appStrings.current.tabDiagnostics) {
            captureCard
            pipelineCard
            buildCard
            telemetryCard
        }
    }

    private var captureCard: some View {
        SectionCard(title: appStrings.current.captureSectionTitle) {
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

    private var pipelineCard: some View {
        SectionCard(title: appStrings.current.pipelineSectionTitle) {
            VStack(spacing: 6) {
                StatRow(
                    key: "vadState",
                    value: model.isCapturing ? model.vadState.description : "",
                    pending: !model.isCapturing
                )
                StatRow(key: "asrProviderId", value: model.asrProviderId, pending: false)
                StatRow(key: "asr support", value: appStrings.current.asrSupportNotClaimed, pending: false)
                StatRow(
                    key: "bufferDepth",
                    value: model.isCapturing ? String(model.bufferDepth) : "",
                    pending: !model.isCapturing
                )
                StatRow(key: "latency p95 (WS6)", value: "", pending: true)
            }
        }
    }

    private var buildCard: some View {
        SectionCard(title: appStrings.current.buildDeviceSectionTitle) {
            VStack(spacing: 6) {
                StatRow(key: "appBuild", value: appBuild, pending: appBuild == "—")
                StatRow(key: "osVersion", value: osVersion, pending: false)
                StatRow(key: "deviceTier", value: "", pending: true)
                StatRow(key: "runtimeVersions", value: "", pending: true)
            }
        }
    }

    private var telemetryCard: some View {
        SectionCard(
            title: appStrings.current.telemetrySectionTitle,
            subtitle: appStrings.current.telemetryPendingHint
        ) {
            StatRow(key: appStrings.current.telemetryNoEventsYet, value: "", pending: true)
        }
    }
}
