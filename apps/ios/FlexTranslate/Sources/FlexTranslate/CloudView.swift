import SwiftUI

// Облако / Cloud (tab 3, Settings) — opt-in cloud, default OFF, honest disclosure.
// Also hosts the in-app RU/EN interface-language switcher (mirrors Android CloudScreen).
struct CloudView: View {
    @ObservedObject var appStrings: AppStrings

    @State private var states: [CloudOptInState] = CloudProviderRegistry.providers.map {
        CloudOptInState(
            providerId: $0.providerId,
            userConsented: false,
            disclosureAccepted: false,
            credential: nil,
            networkState: "offline"
        )
    }

    var body: some View {
        TabScaffold(title: appStrings.current.cloudTitle) {
            policyNote
            languageSwitcher
            ForEach(Array(states.enumerated()), id: \.element.providerId) { index, state in
                CloudProviderCard(
                    title: title(for: state.providerId),
                    role: role(for: state.providerId),
                    state: state,
                    strings: appStrings.current,
                    onToggle: { toggle(at: index) },
                    onAcceptDisclosure: { acceptDisclosure(at: index) }
                )
            }
        }
    }

    // MARK: - Policy note

    private var policyNote: some View {
        Text(appStrings.current.cloudHeader)
            .font(.system(size: 13))
            .foregroundStyle(FlexTheme.mutedText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .panel()
    }

    // MARK: - RU/EN interface-language switcher

    private var languageSwitcher: some View {
        SectionCard(
            title: appStrings.current.interfaceLanguageTitle,
            subtitle: appStrings.current.interfaceLanguageHint
        ) {
            HStack(spacing: 12) {
                ForEach(AppLanguage.allCases, id: \.self) { lang in
                    Button {
                        appStrings.switchTo(lang)
                    } label: {
                        Text(lang.nativeLabel)
                            .font(.system(size: 14, weight: appStrings.current.tabLive == StringsRu().tabLive && lang == .ru
                                         || appStrings.current.tabLive == StringsEn().tabLive && lang == .en
                                         ? .semibold : .regular))
                            .foregroundStyle(isCurrentLang(lang) ? FlexTheme.text : FlexTheme.mutedText)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity)
                            .background(isCurrentLang(lang) ? FlexTheme.primary.opacity(0.2) : FlexTheme.elevated)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .strokeBorder(isCurrentLang(lang) ? FlexTheme.primary : Color.clear, lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("cloud.lang.\(lang.rawValue)")
                }
            }
        }
    }

    private func isCurrentLang(_ lang: AppLanguage) -> Bool {
        AppLanguageStore.shared.load() == lang
    }

    // MARK: - Mutations

    private func toggle(at index: Int) {
        let c = states[index]
        states[index] = CloudOptInState(
            providerId: c.providerId,
            userConsented: !c.userConsented,
            disclosureAccepted: c.disclosureAccepted,
            credential: c.credential,
            networkState: c.networkState
        )
    }

    func acceptDisclosure(at index: Int) {
        let c = states[index]
        states[index] = CloudOptInState(
            providerId: c.providerId,
            userConsented: c.userConsented,
            disclosureAccepted: !c.disclosureAccepted,
            credential: c.credential,
            networkState: c.networkState
        )
    }

    // MARK: - Provider copy (localised via Strings)

    private func title(for id: String) -> String {
        switch id {
        case "cloud-stt-recognition-fallback":
            return appStrings.current.tabLive == StringsRu().tabLive
                ? "Cloud STT · fallback распознавания"
                : "Cloud STT · recognition fallback"
        case "gemini-live-assistant":
            return appStrings.current.tabLive == StringsRu().tabLive
                ? "Gemini Live · realtime ассистент"
                : "Gemini Live · realtime assistant"
        case "gemini-batch-audio-enrichment":
            return appStrings.current.tabLive == StringsRu().tabLive
                ? "Gemini batch · async обогащение"
                : "Gemini batch · async enrichment"
        default:
            return id
        }
    }

    private func role(for id: String) -> String {
        switch id {
        case "cloud-stt-recognition-fallback":
            return appStrings.current.tabLive == StringsRu().tabLive
                ? "Облачное распознавание как fallback, только при явном включении."
                : "Cloud recognition as a fallback when the offline model can't keep up."
        case "gemini-live-assistant":
            return appStrings.current.tabLive == StringsRu().tabLive
                ? "Realtime-ассистент поверх живого эфира."
                : "Realtime assistant over live audio (low latency)."
        case "gemini-batch-audio-enrichment":
            return appStrings.current.tabLive == StringsRu().tabLive
                ? "Асинхронное обогащение аудио после сессии."
                : "Asynchronous batch enrichment of recorded fragments."
        default:
            return ""
        }
    }
}

// MARK: - CloudProviderCard

private struct CloudProviderCard: View {
    let title: String
    let role: String
    let state: CloudOptInState
    let strings: any Strings
    let onToggle: () -> Void
    let onAcceptDisclosure: () -> Void

    @State private var disclosureExpanded = false

    private var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private var canStart: Bool { state.canStart(nowEpochMs: nowMs) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(FlexTheme.text)
                    Text(role)
                        .font(.system(size: 13))
                        .foregroundStyle(FlexTheme.mutedText)
                }
                Spacer(minLength: 8)
                Toggle("", isOn: Binding(get: { state.userConsented }, set: { _ in onToggle() }))
                    .labelsHidden()
                    .tint(FlexTheme.primary)
                    .accessibilityIdentifier("cloud.toggle.\(state.providerId)")
            }

            Badge(
                text: canStart ? strings.readyToStart : strings.disabledMissing(""),
                color: canStart ? FlexStatus.green : FlexTheme.mutedText
            )

            preconditions

            Button {
                disclosureExpanded.toggle()
            } label: {
                HStack(spacing: 6) {
                    Text(disclosureExpanded ? strings.hideDisclosure : strings.showDisclosure)
                        .font(.system(size: 12, weight: .medium))
                    Image(systemName: disclosureExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                }
                .foregroundStyle(FlexTheme.primary)
            }

            if disclosureExpanded {
                Text(strings.backendMediationHint)
                    .font(.system(size: 12))
                    .foregroundStyle(FlexTheme.mutedText)

                HStack(spacing: 10) {
                    Toggle(
                        "",
                        isOn: Binding(
                            get: { state.disclosureAccepted },
                            set: { _ in onAcceptDisclosure() }
                        )
                    )
                    .labelsHidden()
                    .tint(FlexTheme.primary)
                    .accessibilityIdentifier("cloud.disclosure.\(state.providerId)")

                    Text(strings.acceptDisclosure)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(state.disclosureAccepted ? FlexTheme.text : FlexTheme.mutedText)
                }
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel()
    }

    private var preconditions: some View {
        VStack(alignment: .leading, spacing: 4) {
            preconditionRow(label: strings.missingConsent, met: state.userConsented)
            preconditionRow(label: strings.missingDisclosure, met: state.disclosureAccepted)
            preconditionRow(label: strings.missingOnline, met: state.networkState == "online")
            preconditionRow(
                label: strings.missingEphemeralToken,
                met: state.credential?.isEphemeral(nowEpochMs: nowMs) == true
            )
        }
    }

    private func preconditionRow(label: String, met: Bool) -> some View {
        HStack(spacing: 6) {
            Image(systemName: met ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 11))
                .foregroundStyle(met ? FlexStatus.green : FlexTheme.mutedText)
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(met ? FlexTheme.text : FlexTheme.mutedText)
        }
    }
}
