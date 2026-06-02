import SwiftUI

// Языки / Languages (tab 1) — pick source/target, show honest per-pair support,
// MT model picker, and MT routing mode picker.
// Now takes the shared LiveSessionModel so language/routing changes propagate to Live.
struct LanguagesView: View {
    @ObservedObject var session: LiveSessionModel
    @EnvironmentObject private var appStrings: AppStrings

    var body: some View {
        TabScaffold(title: appStrings.current.tabLanguages) {
            selectors
            pairSupport
            routingModePicker
            note
        }
    }

    private var activePair: String {
        "\(session.sourceLanguage.displayCode) → \(session.targetLanguage.displayCode)"
    }

    // MARK: - Source / target selectors

    private var selectors: some View {
        SectionCard(title: appStrings.current.languagePairTitle) {
            HStack(spacing: 12) {
                languagePicker(
                    title: appStrings.current.sourceLabel,
                    current: session.sourceLanguage,
                    onSelect: { session.selectSource($0) }
                )
                Button {
                    session.swapLanguages()
                } label: {
                    Image(systemName: "arrow.left.arrow.right")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(FlexTheme.primary)
                        .frame(width: 40, height: 40)
                        .background(FlexTheme.elevated)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .accessibilityLabel(appStrings.current.swapLanguagesDescription)
                .accessibilityIdentifier("languages.swap")
                languagePicker(
                    title: appStrings.current.targetLabel,
                    current: session.targetLanguage,
                    onSelect: { session.selectTarget($0) }
                )
            }
        }
    }

    private func languagePicker(
        title: String,
        current: FlexLanguage,
        onSelect: @escaping (FlexLanguage) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(FlexTheme.mutedText)
            Menu {
                ForEach(FlexLanguage.allCases, id: \.self) { lang in
                    Button(lang.displayCode) { onSelect(lang) }
                }
            } label: {
                HStack {
                    Text(current.displayCode)
                        .font(.system(size: 15, weight: .semibold, design: .monospaced))
                        .foregroundStyle(FlexTheme.text)
                    Spacer()
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(FlexTheme.mutedText)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity)
                .background(FlexTheme.elevated)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Pair support

    private var pairSupport: some View {
        SectionCard(title: appStrings.current.pairSupportTitle(activePair)) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Badge(text: appStrings.current.offlineAsrAdapterReady, color: FlexStatus.amber)
                    Spacer(minLength: 0)
                }
                HStack(spacing: 8) {
                    Badge(text: appStrings.current.offlineTranslationNotClaimed, color: FlexStatus.red)
                    Spacer(minLength: 0)
                }
                Text(appStrings.current.offlineTranslationNotClaimedLong)
                    .font(.system(size: 13))
                    .foregroundStyle(FlexTheme.mutedText)
            }
        }
    }

    // MARK: - MT routing mode picker

    private var routingModePicker: some View {
        SectionCard(
            title: appStrings.current.mtRoutingModeTitle,
            subtitle: appStrings.current.mtRoutingModeAutoHint
        ) {
            VStack(spacing: 8) {
                routingModeRow(
                    mode: .auto,
                    label: appStrings.current.mtRoutingModeAuto
                )
                routingModeRow(
                    mode: .onDevice,
                    label: appStrings.current.mtRoutingModeOnDevice
                )
                routingModeRow(
                    mode: .cloud,
                    label: appStrings.current.mtRoutingModeCloud
                )
            }
        }
    }

    private func routingModeRow(mode: MtRoutingMode, label: String) -> some View {
        Button {
            session.selectRoutingMode(mode)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: session.selectedRoutingMode == mode
                      ? "largecircle.fill.circle"
                      : "circle")
                    .font(.system(size: 16))
                    .foregroundStyle(session.selectedRoutingMode == mode
                                     ? FlexTheme.primary : FlexTheme.mutedText)
                Text(label)
                    .font(.system(size: 14, weight: session.selectedRoutingMode == mode ? .semibold : .regular))
                    .foregroundStyle(session.selectedRoutingMode == mode ? FlexTheme.text : FlexTheme.mutedText)
                Spacer()
                if session.selectedRoutingMode == mode {
                    Badge(text: appStrings.current.selected, color: FlexTheme.primary)
                }
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("languages.routing.\(mode.rawValue)")
    }

    // MARK: - Footer note

    private var note: some View {
        Text(appStrings.current.supportFromBenchmarksFooter)
            .font(.system(size: 12))
            .foregroundStyle(FlexTheme.mutedText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
    }
}
