import SwiftUI

// Shared aquacard-dark UI primitives for the WS1 shell.
// Depth via layered surfaces only — no shadows, no gradients (see design tokens).

// Semantic status palette (reused from the aquacard difficulty palette).
// Exact channel / 255 from canonical hex — no approximations.
enum FlexStatus {
    static let green = Color(red: 34.0 / 255.0, green: 197.0 / 255.0, blue: 94.0 / 255.0) // #22C55E ok/ready
    static let amber = Color(red: 245.0 / 255.0, green: 158.0 / 255.0, blue: 11.0 / 255.0) // #F59E0B warn/gated
    static let red = Color(red: 239.0 / 255.0, green: 68.0 / 255.0, blue: 68.0 / 255.0) // #EF4444 unsupported/error
    static let info = FlexTheme.primary // #7C9CFF cloud/info
}

// Pill badge — tinted background (alpha 0.16) + colored label, radius 5.
struct Badge: View {
    let text: String
    var color: Color = FlexTheme.primary
    var monospaced: Bool = false

    var body: some View {
        Text(text)
            .font(monospaced ? .system(size: 11, weight: .medium, design: .monospaced) : .system(size: 11, weight: .medium))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.16))
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            .accessibilityLabel(Text(text))
    }
}

// Titled surface card. Children stack vertically, leading aligned.
struct SectionCard<Content: View>: View {
    let title: String?
    var subtitle: String?
    @ViewBuilder var content: () -> Content

    init(title: String? = nil, subtitle: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(FlexTheme.text)
            }
            if let subtitle {
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(FlexTheme.mutedText)
            }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel()
    }
}

// Monospace key/value row for diagnostics and data readouts.
// `pending` renders the value as a muted em dash so missing data is never faked.
struct StatRow: View {
    let key: String
    let value: String
    var pending: Bool = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(key)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(FlexTheme.mutedText)
            Spacer(minLength: 8)
            Text(pending ? "—" : value)
                .font(.system(size: 13, weight: .regular, design: .monospaced))
                .foregroundStyle(pending ? FlexTheme.mutedText : FlexTheme.text)
                .multilineTextAlignment(.trailing)
        }
        .frame(maxWidth: .infinity)
    }
}

// Global honest demo banner — pinned at the top of every tab's content.
// Reads from AppStrings so the text localises when the user switches language.
struct DemoBanner: View {
    @EnvironmentObject private var appStrings: AppStrings

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 11, weight: .semibold))
            Text(appStrings.current.demoBanner)
                .font(.system(size: 11, weight: .medium))
        }
        .foregroundStyle(FlexStatus.amber)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlexStatus.amber.opacity(0.16))
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .accessibilityIdentifier("demo.banner")
    }
}

// Common scroll container: dark background, demo banner pinned on top, padded content.
struct TabScaffold<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                DemoBanner()
                content()
            }
            .padding(16)
        }
        .background(FlexTheme.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
