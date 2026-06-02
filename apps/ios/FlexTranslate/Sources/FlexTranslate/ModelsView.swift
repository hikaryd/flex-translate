import SwiftUI

// Модели / Models & offline packs (tab 2) — manage offline ASR/MT packs honestly.
// Weights are NOT bundled; nothing is marked "supported".
struct ModelsView: View {
    @EnvironmentObject private var appStrings: AppStrings

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
                    strings: appStrings.current
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
                    strings: appStrings.current
                )
            }
        }
    }
}

// One offline-pack row. WS1: every pack is "not installed" (weights not bundled).
private struct PackRow: View {
    let packId: String
    let tier: String
    let detail: String
    let strings: any Strings

    private let isOnline = false

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

            StatRow(key: strings.sizeUnknown, value: "", pending: true)

            HStack(spacing: 10) {
                Badge(text: strings.statusNotInstalled, color: FlexTheme.mutedText)
                Spacer(minLength: 0)
                Button {
                    // Download lands in a later workstream; no-op placeholder.
                } label: {
                    Text(strings.download)
                        .font(.system(size: 13, weight: .semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                }
                .foregroundStyle(isOnline ? FlexTheme.onAccent : FlexTheme.mutedText)
                .background(isOnline ? FlexTheme.primary : FlexTheme.elevated)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .disabled(!isOnline)
            }

            if !isOnline {
                Text(strings.onlineOnlyLine)
                    .font(.system(size: 11))
                    .foregroundStyle(FlexTheme.mutedText)
            }
            StatRow(key: "checksum / rollback", value: "", pending: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel()
    }
}
