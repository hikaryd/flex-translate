import SwiftUI

// Эфир / Live — главный экран живого перевода (вкладка 0).
// Режим диалога: лог разговора как в чате (пузыри слева/справа), переключение «кто говорит» + очистка.
// Промежуточный транскрипт показываем внизу, пока идёт запись.
struct LiveView: View {
    @ObservedObject var model: LiveSessionModel
    @EnvironmentObject private var appStrings: AppStrings

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabScaffold(title: appStrings.current.tabLive) {
            statusStrip
            micMeter
            conversationLogPanel
            partialPanel
            captureControl
        }
        .task {
            await model.refreshPermission()
        }
        .onDisappear {
            model.stopIfNeeded()
        }
        .onChange(of: scenePhase) { newPhase in
            if newPhase == .background {
                model.stopIfNeeded()
            }
        }
    }

    // MARK: - Status strip

    private var statusStrip: some View {
        HStack(spacing: 8) {
            Badge(
                text: model.cloudActive ? "cloud" : appStrings.current.modeOffline,
                color: model.cloudActive ? FlexStatus.info : FlexTheme.primary
            )
            Badge(text: model.languagePair, color: FlexTheme.secondary, monospaced: true)
            readinessBadge
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private var readinessBadge: some View {
        switch model.offlineState {
        case .readyOfflineAsr:
            Badge(text: appStrings.current.micReady, color: FlexStatus.green)
        case let .captureBlocked(reason):
            Badge(text: reason, color: FlexStatus.red)
        case let .missingOfflinePack(packId):
            Badge(text: appStrings.current.missingPackBadge(packId), color: FlexStatus.amber)
        case .unsupportedOfflineTranslation:
            Badge(text: appStrings.current.cloudDisabledBadge, color: FlexTheme.mutedText)
        case .cloudDisabled:
            Badge(text: appStrings.current.modeOffline, color: FlexTheme.mutedText)
        }
    }

    // MARK: - Mic meter

    private var micMeter: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(appStrings.current.micLevelTitle)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(FlexTheme.mutedText)
                Spacer()
                if model.isCapturing {
                    Badge(
                        text: model.speechActive ? appStrings.current.speech : appStrings.current.silence,
                        color: model.speechActive ? FlexTheme.primary : FlexTheme.mutedText
                    )
                } else {
                    Text("idle")
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(FlexTheme.mutedText)
                }
            }
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

    // MARK: - Conversation log (dialogue chat UI)

    private var conversationLogPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(appStrings.current.translationTitle)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(FlexTheme.text)
                Spacer()
                // Кнопка «поменять, кто говорит»
                Button {
                    model.swapLanguages()
                } label: {
                    Image(systemName: "arrow.left.arrow.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(FlexTheme.primary)
                        .frame(width: 32, height: 32)
                        .background(FlexTheme.elevated)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .accessibilityLabel(appStrings.current.swapLanguagesDescription)
                .accessibilityIdentifier("live.swapLanguages")

                // Кнопка очистки
                if !model.conversationLog.isEmpty {
                    Button {
                        model.clearDialogue()
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(FlexStatus.red)
                            .frame(width: 32, height: 32)
                            .background(FlexTheme.elevated)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                    .accessibilityLabel(appStrings.current.dialogueClearButton)
                    .accessibilityIdentifier("live.clearDialogue")
                }
            }

            if model.conversationLog.isEmpty {
                Text(appStrings.current.dialogueEmptyHint)
                    .font(.system(size: 13))
                    .foregroundStyle(FlexTheme.mutedText)
                    .frame(maxWidth: .infinity, minHeight: 120, alignment: .center)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 8)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            ForEach(model.conversationLog) { turn in
                                DialogueTurnBubble(turn: turn, model: model)
                                    .id(turn.id)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .frame(maxHeight: 320)
                    .onChange(of: model.conversationLog.count) { _ in
                        if let last = model.conversationLog.last {
                            withAnimation(.easeOut(duration: 0.2)) {
                                proxy.scrollTo(last.id, anchor: .bottom)
                            }
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel()
    }

    // MARK: - Partial transcript panel (shown while capturing)

    @ViewBuilder
    private var partialPanel: some View {
        if model.isCapturing && !model.partialTranscript.isEmpty {
            HStack(spacing: 8) {
                ProgressView()
                    .scaleEffect(0.7)
                    .tint(FlexTheme.mutedText)
                Text(model.partialTranscript)
                    .font(.system(size: 14))
                    .italic()
                    .foregroundStyle(FlexTheme.mutedText)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(FlexTheme.elevated)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }

    // MARK: - Capture control

    private var captureControl: some View {
        VStack(spacing: 8) {
            Button {
                model.toggleCapture()
            } label: {
                Text(model.isCapturing ? appStrings.current.stop : appStrings.current.listen)
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

    // MARK: - A2 demo panel

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
                    Text(model.testAudioRunning
                         ? appStrings.current.demoRecognizing
                         : appStrings.current.demoRecognizeButton("RU"))
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

            Button {
                model.runTestTranslation()
            } label: {
                HStack {
                    if model.testAudioRunning {
                        ProgressView().scaleEffect(0.7)
                    }
                    Text(model.testAudioRunning
                         ? appStrings.current.translating
                         : "▶ тест-перевод (M2M-100 offline MT)")
                        .font(.system(size: 13, weight: .medium))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            .foregroundStyle(FlexTheme.primary)
            .background(FlexTheme.elevated)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .disabled(model.testAudioRunning)
            .accessibilityIdentifier("live.testMT")
        }
    }
}

// MARK: - Dialogue turn bubble

private struct DialogueTurnBubble: View {
    let turn: DialogueTurn
    @ObservedObject var model: LiveSessionModel
    @EnvironmentObject private var appStrings: AppStrings

    /// Реплики на исходном языке прижимаем вправо (текущий говорящий),
    /// реплики на языке собеседника — влево (другая сторона).
    private var isSourceSpeaker: Bool {
        turn.spokenLanguage == model.sourceLanguage
    }

    var body: some View {
        VStack(alignment: isSourceSpeaker ? .trailing : .leading, spacing: 4) {
            // Бейдж языка
            Text(appStrings.current.dialogueSpeakingLabel(turn.spokenLanguage.label))
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(FlexTheme.mutedText)
                .padding(.horizontal, isSourceSpeaker ? 4 : 0)

            // Пузырь с оригиналом
            Text(turn.originalText)
                .font(.system(size: 15))
                .foregroundStyle(FlexTheme.text)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(isSourceSpeaker ? FlexTheme.primary.opacity(0.18) : FlexTheme.elevated)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .frame(maxWidth: 280, alignment: isSourceSpeaker ? .trailing : .leading)

            // Слот перевода
            if turn.translationPending {
                HStack(spacing: 6) {
                    ProgressView().scaleEffect(0.65).tint(FlexTheme.mutedText)
                    Text(appStrings.current.dialoguePendingTranslation)
                        .font(.system(size: 12))
                        .italic()
                        .foregroundStyle(FlexTheme.mutedText)
                }
                .padding(.horizontal, isSourceSpeaker ? 4 : 0)
            } else if let translatedText = turn.translatedText {
                VStack(alignment: isSourceSpeaker ? .trailing : .leading, spacing: 2) {
                    Text(translatedText)
                        .font(.system(size: 13))
                        .foregroundStyle(FlexTheme.mutedText)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(FlexTheme.surface)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .frame(maxWidth: 280, alignment: isSourceSpeaker ? .trailing : .leading)
                    if let engine = turn.mtEngineUsed {
                        Text(engine)
                            .font(.system(size: 10))
                            .foregroundStyle(FlexTheme.mutedText.opacity(0.7))
                            .padding(.horizontal, isSourceSpeaker ? 4 : 0)
                    }
                }
            } else if let reason = turn.translationReason {
                Text(reason)
                    .font(.system(size: 12))
                    .foregroundStyle(FlexStatus.amber)
                    .padding(.horizontal, isSourceSpeaker ? 4 : 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: isSourceSpeaker ? .trailing : .leading)
    }
}
