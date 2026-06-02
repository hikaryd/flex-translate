import SwiftUI

// Root navigation shell — 5-tab TabView, each a NavigationStack.
// Mirrors the aquacard ContentView pattern: tint = accent, dark color scheme.
struct ContentView: View {
    // Shared live session: the capture/VAD state produced on the Эфир tab is the
    // same instance observed by Диагностика, so real vadState is visible in both.
    @StateObject private var session = LiveSessionModel()

    var body: some View {
        TabView {
            NavigationStack { LiveView(model: session) }
                .tabItem { Label("Эфир", systemImage: "waveform") }
                .accessibilityIdentifier("tab.live")
                .onAppear {
                    // CI/demo: launch with -MT_TEST to auto-run offline MT evidence capture.
                    if CommandLine.arguments.contains("-MT_TEST") {
                        Task { @MainActor in
                            try? await Task.sleep(nanoseconds: 2_000_000_000)
                            session.runTestTranslation()
                        }
                    }
                }

            NavigationStack { LanguagesView() }
                .tabItem { Label("Языки", systemImage: "globe") }
                .accessibilityIdentifier("tab.languages")

            NavigationStack { ModelsView() }
                .tabItem { Label("Модели", systemImage: "square.and.arrow.down") }
                .accessibilityIdentifier("tab.models")

            NavigationStack { CloudView() }
                .tabItem { Label("Облако", systemImage: "cloud") }
                .accessibilityIdentifier("tab.cloud")

            NavigationStack { DiagnosticsView(model: session) }
                .tabItem { Label("Диагностика", systemImage: "gauge") }
                .accessibilityIdentifier("tab.diagnostics")
        }
        .tint(FlexTheme.primary)
        .preferredColorScheme(.dark)
    }
}
