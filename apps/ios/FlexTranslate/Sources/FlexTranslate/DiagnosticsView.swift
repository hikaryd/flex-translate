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
                // Real ASR latency p95 from telemetry samples — "—" when none yet.
                let asrPercentiles = model.telemetrySink.latencyPercentiles(
                    eventType: TelemetrySink.evtAsrFinal,
                    payloadKey: "latency_ms"
                )
                StatRow(
                    key: "asr latency p95",
                    value: asrPercentiles.p95Ms.map { "\($0) ms" } ?? "—",
                    pending: asrPercentiles.sampleCount == 0
                )
            }
        }
    }

    private var buildCard: some View {
        SectionCard(title: appStrings.current.buildDeviceSectionTitle) {
            VStack(spacing: 6) {
                StatRow(key: "appBuild", value: appBuild, pending: appBuild == "—")
                StatRow(key: "osVersion", value: osVersion, pending: false)
                StatRow(
                    key: "deviceTier",
                    value: model.telemetryCtx.deviceTier,
                    pending: false
                )
                StatRow(
                    key: "sessionId",
                    value: String(model.telemetryCtx.sessionId.prefix(8)) + "…",
                    pending: false
                )
            }
        }
    }

    private var telemetryCard: some View {
        let recentEvents = model.telemetrySink.recent(n: 8)
        let mtPercentiles = model.telemetrySink.latencyPercentiles(
            eventType: TelemetrySink.evtMtEnd,
            payloadKey: "latency_ms"
        )

        return SectionCard(
            title: appStrings.current.telemetrySectionTitle,
            subtitle: nil
        ) {
            VStack(alignment: .leading, spacing: 6) {
                // p50 / p95 MT latency from real samples.
                StatRow(
                    key: appStrings.current.telemetryMtP50,
                    value: mtPercentiles.p50Ms.map { "\($0) ms (n=\(mtPercentiles.sampleCount))" } ?? "—",
                    pending: mtPercentiles.sampleCount == 0
                )
                StatRow(
                    key: appStrings.current.telemetryMtP95,
                    value: mtPercentiles.p95Ms.map { "\($0) ms" } ?? "—",
                    pending: mtPercentiles.sampleCount == 0
                )
                StatRow(
                    key: appStrings.current.telemetryTotalEvents,
                    value: String(model.telemetrySink.totalAccepted),
                    pending: false
                )

                Divider().opacity(0.4)

                // Recent events list — newest last.
                if recentEvents.isEmpty {
                    StatRow(
                        key: appStrings.current.telemetryNoEventsYet,
                        value: "",
                        pending: true
                    )
                } else {
                    ForEach(recentEvents.suffix(5), id: \.monotonicTsMs) { event in
                        HStack {
                            Text("+\(event.monotonicTsMs % 100_000) ms")
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .frame(width: 80, alignment: .leading)
                            Text(event.eventType)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(.primary)
                            Spacer()
                        }
                    }
                }
            }
        }
    }
}
