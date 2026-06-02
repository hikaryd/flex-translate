import SwiftUI

// Модели / Models & offline packs (tab 2) — manage offline ASR/MT packs honestly.
// Weights are NOT bundled; download happens in-app via ModelDownloadManager.
struct ModelsView: View {
    @EnvironmentObject private var appStrings: AppStrings
    @ObservedObject private var downloadManager = ModelDownloadManager.shared

    var body: some View {
        TabScaffold(title: appStrings.current.tabModels) {
            header
            asrSection
            mtSection
        }
    }

    private var header: some View {
        Text(appStrings.current.offlinePacksHeader)
            .font(.system(size: 13))
            .foregroundStyle(FlexTheme.mutedText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .panel()
    }

    private var asrSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("ASR \(appStrings.current.offlinePacksTitle) (RU / EN)")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(FlexTheme.text)
                .padding(.horizontal, 4)
            ForEach(AsrCandidateRegistry.candidates, id: \.id) { candidate in
                PackRow(
                    packId: candidate.id,
                    tier: candidate.deviceTiers.joined(separator: "/"),
                    detail: "\(candidate.language.uppercased()) · \(candidate.runtime)",
                    strings: appStrings.current,
                    downloadManager: downloadManager
                )
            }
        }
    }

    private var mtSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MT \(appStrings.current.offlinePacksTitle) (RU↔EN)")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(FlexTheme.text)
                .padding(.horizontal, 4)
            ForEach(TranslationCandidateRegistry.candidates, id: \.id) { candidate in
                PackRow(
                    packId: candidate.id,
                    tier: candidate.targetTiers.joined(separator: "/"),
                    detail: candidate.languagePairs.joined(separator: ", "),
                    strings: appStrings.current,
                    downloadManager: downloadManager
                )
            }
        }
    }
}

// One offline-pack row with real Download/Cancel/Delete + live progress.
private struct PackRow: View {
    let packId: String
    let tier: String
    let detail: String
    let strings: any Strings
    @ObservedObject var downloadManager: ModelDownloadManager

    private var state: ModelDownloadManager.DownloadState {
        downloadManager.state(for: packId)
    }

    private var spec: ModelDownloadSpec? {
        ModelDownloadSpecs.forModelId(packId)
    }

    private var isInstalled: Bool {
        // Check via the model store whether the pack is installed (checksum-verified by engine).
        if let asrSpec = AsrModelSpecs.all.first(where: { $0.modelId == packId }) {
            return AsrModelStore.shared.isInstalled(asrSpec)
        }
        if let mtSpec = MtModelSpecs.all.first(where: { $0.modelId == packId }) {
            return MtModelStore.shared.isInstalled(mtSpec)
        }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(packId)
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(FlexTheme.text)
                    Text(detail)
                        .font(.system(size: 12))
                        .foregroundStyle(FlexTheme.mutedText)
                }
                Spacer(minLength: 8)
                Badge(text: "tier \(tier)", color: FlexTheme.secondary, monospaced: true)
            }

            sizeRow
            statusRow
            actionRow

            if !downloadManager.isOnline() {
                Text(strings.onlineOnlyLine)
                    .font(.system(size: 11))
                    .foregroundStyle(FlexTheme.mutedText)
            }

            if spec != nil {
                Text(strings.downloadsOverNetworkLine)
                    .font(.system(size: 11))
                    .foregroundStyle(FlexTheme.mutedText)
            } else {
                Text(strings.sourceNotConfiguredLine)
                    .font(.system(size: 11))
                    .foregroundStyle(FlexTheme.mutedText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel()
    }

    @ViewBuilder
    private var sizeRow: some View {
        if let spec = spec {
            StatRow(
                key: "size",
                value: String(format: "%.1f MB", spec.totalMb),
                pending: false
            )
        } else {
            StatRow(key: strings.sizeUnknown, value: "", pending: true)
        }
    }

    @ViewBuilder
    private var statusRow: some View {
        switch state {
        case .idle:
            if isInstalled {
                Badge(text: strings.installed, color: FlexStatus.green)
            } else {
                Badge(text: strings.statusNotInstalled, color: FlexTheme.mutedText)
            }
        case .downloading(let done, let total, let file):
            VStack(alignment: .leading, spacing: 4) {
                if let file = file {
                    Text(strings.downloadingFile(file))
                        .font(.system(size: 11))
                        .foregroundStyle(FlexTheme.mutedText)
                }
                ProgressView(value: total > 0 ? Double(done) / Double(total) : 0)
                    .tint(FlexTheme.primary)
                Text(progressLabel(done: done, total: total))
                    .font(.system(size: 11))
                    .foregroundStyle(FlexTheme.mutedText)
            }
        case .done:
            Badge(text: strings.installed, color: FlexStatus.green)
        case .cancelled:
            VStack(alignment: .leading, spacing: 4) {
                Badge(text: strings.statusCancelled, color: FlexTheme.mutedText)
                Text(strings.downloadCancelledLine)
                    .font(.system(size: 11))
                    .foregroundStyle(FlexTheme.mutedText)
            }
        case .failed(let msg):
            VStack(alignment: .leading, spacing: 4) {
                Badge(text: strings.statusError, color: FlexStatus.red)
                Text(strings.downloadFailedLine(msg))
                    .font(.system(size: 11))
                    .foregroundStyle(FlexTheme.mutedText)
            }
        }
    }

    @ViewBuilder
    private var actionRow: some View {
        HStack(spacing: 10) {
            switch state {
            case .idle:
                if isInstalled {
                    Spacer(minLength: 0)
                    Button {
                        downloadManager.delete(modelId: packId)
                    } label: {
                        actionLabel(strings.delete)
                    }
                    .foregroundStyle(FlexTheme.mutedText)
                    .background(FlexTheme.elevated)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                } else {
                    Spacer(minLength: 0)
                    let canDownload = downloadManager.isOnline() && spec != nil
                    Button {
                        if canDownload { downloadManager.start(modelId: packId) }
                    } label: {
                        actionLabel(strings.download)
                    }
                    .foregroundStyle(canDownload ? FlexTheme.onAccent : FlexTheme.mutedText)
                    .background(canDownload ? FlexTheme.primary : FlexTheme.elevated)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .disabled(!canDownload)
                }

            case .downloading:
                Spacer(minLength: 0)
                Button {
                    downloadManager.cancel(modelId: packId)
                } label: {
                    actionLabel(strings.cancel)
                }
                .foregroundStyle(FlexTheme.mutedText)
                .background(FlexTheme.elevated)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            case .done:
                Spacer(minLength: 0)
                Button {
                    downloadManager.delete(modelId: packId)
                } label: {
                    actionLabel(strings.delete)
                }
                .foregroundStyle(FlexTheme.mutedText)
                .background(FlexTheme.elevated)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            case .cancelled, .failed:
                Spacer(minLength: 0)
                let canRetry = downloadManager.isOnline() && spec != nil
                Button {
                    if canRetry {
                        downloadManager.reset(modelId: packId)
                        downloadManager.start(modelId: packId)
                    }
                } label: {
                    actionLabel(strings.retry)
                }
                .foregroundStyle(canRetry ? FlexTheme.onAccent : FlexTheme.mutedText)
                .background(canRetry ? FlexTheme.primary : FlexTheme.elevated)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .disabled(!canRetry)
            }
        }
    }

    private func actionLabel(_ label: String) -> some View {
        Text(label)
            .font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
    }

    private func progressLabel(done: Int64, total: Int64) -> String {
        let doneMB = Double(done) / (1024 * 1024)
        let totalMB = Double(total) / (1024 * 1024)
        let pct = total > 0 ? Int(Double(done) / Double(total) * 100) : 0
        return String(format: "%.1f / %.1f MB (%d%%)", doneMB, totalMB, pct)
    }
}
