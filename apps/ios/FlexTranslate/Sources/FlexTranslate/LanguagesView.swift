import SwiftUI

// Языки / Languages (tab 1) — pick source/target, show honest per-pair support.
// Phase-0 scope: RU and EN only.
struct LanguagesView: View {
    @State private var source = "RU"
    @State private var target = "EN"

    private let languages = ["RU", "EN"]

    var body: some View {
        TabScaffold(title: "Языки") {
            selectors
            pairSupport
            note
        }
    }

    private var activePair: String { "\(source.lowercased())->\(target.lowercased())" }

    // 1. Source / target selectors with a swap control.
    private var selectors: some View {
        SectionCard(title: "Языковая пара") {
            HStack(spacing: 12) {
                languagePicker(title: "Источник", selection: $source)
                Button {
                    swap()
                } label: {
                    Image(systemName: "arrow.left.arrow.right")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(FlexTheme.primary)
                        .frame(width: 40, height: 40)
                        .background(FlexTheme.elevated)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .accessibilityIdentifier("languages.swap")
                languagePicker(title: "Цель", selection: $target)
            }
        }
    }

    private func languagePicker(title: String, selection: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(FlexTheme.mutedText)
            Menu {
                ForEach(languages, id: \.self) { lang in
                    Button(lang) { selection.wrappedValue = lang }
                }
            } label: {
                HStack {
                    Text(selection.wrappedValue)
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

    private func swap() {
        let oldSource = source
        source = target
        target = oldSource
    }

    // 2. Pair support row — benchmark-gated, never claimed.
    private var pairSupport: some View {
        SectionCard(title: "Поддержка пары \(source) → \(target)") {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Badge(text: "offline-ASR: адаптер готов (demo)", color: FlexStatus.amber) // amber = not_claimed, not proven
                    Spacer(minLength: 0)
                }
                HStack(spacing: 8) {
                    Badge(text: "offline-перевод: не заявлен", color: FlexStatus.red)
                    Spacer(minLength: 0)
                }
                Text("offline-перевод: не заявлен (нужны benchmark + модель). Соответствует состоянию UnsupportedOfflineTranslation для \(activePair).")
                    .font(.system(size: 13))
                    .foregroundStyle(FlexTheme.mutedText)
            }
        }
    }

    // 3. Note — support comes from evidence, not intent.
    private var note: some View {
        Text("Поддержка генерируется из benchmark-доказательств, а не из намерений.")
            .font(.system(size: 12))
            .foregroundStyle(FlexTheme.mutedText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
    }
}
