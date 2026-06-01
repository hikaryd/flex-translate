import SwiftUI

// Облако / Cloud (tab 3, Settings) — opt-in cloud, default OFF, honest disclosure.
struct CloudView: View {
    // Per-provider opt-in state, all OFF by default. No bundled credentials.
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
        TabScaffold(title: "Облако") {
            policyNote
            ForEach(Array(states.enumerated()), id: \.element.providerId) { index, state in
                CloudProviderCard(
                    title: Self.title(for: state.providerId),
                    role: Self.role(for: state.providerId),
                    state: state,
                    onToggle: { toggle(at: index) },
                    onAcceptDisclosure: { acceptDisclosure(at: index) }
                )
            }
        }
    }

    private var policyNote: some View {
        Text("Облако выключено по умолчанию · нет silent fallback · нет встроенных API-ключей (backend ephemeral tokens).")
            .font(.system(size: 13))
            .foregroundStyle(FlexTheme.mutedText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .panel()
    }

    // Toggling flips user consent. acceptDisclosure flips disclosureAccepted separately.
    // canStart(now) still requires both + online + ephemeral token — cloud stays OFF by default.
    private func toggle(at index: Int) {
        let current = states[index]
        states[index] = CloudOptInState(
            providerId: current.providerId,
            userConsented: !current.userConsented,
            disclosureAccepted: current.disclosureAccepted,
            credential: current.credential,
            networkState: current.networkState
        )
    }

    // Fix 6: separate disclosure-accept action, matching Android CloudScreen behaviour.
    func acceptDisclosure(at index: Int) {
        let current = states[index]
        states[index] = CloudOptInState(
            providerId: current.providerId,
            userConsented: current.userConsented,
            disclosureAccepted: !current.disclosureAccepted,
            credential: current.credential,
            networkState: current.networkState
        )
    }

    static func title(for id: String) -> String {
        switch id {
        case "cloud-stt-recognition-fallback": return "Cloud STT · fallback распознавания"
        case "gemini-live-assistant": return "Gemini Live · realtime ассистент"
        case "gemini-batch-audio-enrichment": return "Gemini batch · async обогащение"
        default: return id
        }
    }

    static func role(for id: String) -> String {
        switch id {
        case "cloud-stt-recognition-fallback": return "Облачное распознавание как fallback, только при явном включении."
        case "gemini-live-assistant": return "Realtime-ассистент поверх живого эфира."
        case "gemini-batch-audio-enrichment": return "Асинхронное обогащение аудио после сессии."
        default: return ""
        }
    }
}

private struct CloudProviderCard: View {
    let title: String
    let role: String
    let state: CloudOptInState
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

            Badge(text: canStart ? "готов к запуску" : "выключено", color: canStart ? FlexStatus.green : FlexTheme.mutedText)

            preconditions

            Button {
                disclosureExpanded.toggle()
            } label: {
                HStack(spacing: 6) {
                    Text(disclosureExpanded ? "Скрыть раскрытие данных" : "Раскрытие данных и согласие")
                        .font(.system(size: 12, weight: .medium))
                    Image(systemName: disclosureExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                }
                .foregroundStyle(FlexTheme.primary)
            }

            if disclosureExpanded {
                Text("Данные аудио покидают устройство только при включении этого провайдера. Учётные данные — эфемерные backend-токены; ключи в приложение не встроены. Хранение и удаление данных регулируются политикой провайдера.")
                    .font(.system(size: 12))
                    .foregroundStyle(FlexTheme.mutedText)

                // Fix 6: disclosure-accept affordance — matches Android CloudScreen.
                // Cloud still stays OFF until all canStart preconditions hold.
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

                    Text("Принять раскрытие данных")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(state.disclosureAccepted ? FlexTheme.text : FlexTheme.mutedText)
                }
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel()
    }

    // Show exactly which preconditions are unmet, derived from CloudOptInState.
    private var preconditions: some View {
        VStack(alignment: .leading, spacing: 4) {
            preconditionRow(label: "согласие пользователя", met: state.userConsented)
            preconditionRow(label: "принятие раскрытия", met: state.disclosureAccepted)
            preconditionRow(label: "онлайн", met: state.networkState == "online")
            preconditionRow(label: "эфемерный токен", met: state.credential?.isEphemeral(nowEpochMs: nowMs) == true)
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
