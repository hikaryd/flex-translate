import SwiftUI

// Root navigation shell — 5-tab TabView, each a NavigationStack.
// AppStrings is created here and injected as an EnvironmentObject so every
// child view recomposes in the new language when the user flips the toggle.
struct ContentView: View {
    @StateObject private var session = LiveSessionModel()
    @StateObject private var appStrings = AppStrings()

    var body: some View {
        TabView {
            NavigationStack { LiveView(model: session) }
                .tabItem { Label(appStrings.current.tabLive, systemImage: "waveform") }
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

            NavigationStack { LanguagesView(session: session) }
                .tabItem { Label(appStrings.current.tabLanguages, systemImage: "globe") }
                .accessibilityIdentifier("tab.languages")

            NavigationStack { ModelsView() }
                .tabItem { Label(appStrings.current.tabModels, systemImage: "square.and.arrow.down") }
                .accessibilityIdentifier("tab.models")

            NavigationStack { CloudView(appStrings: appStrings) }
                .tabItem { Label(appStrings.current.tabCloud, systemImage: "cloud") }
                .accessibilityIdentifier("tab.cloud")

            NavigationStack { DiagnosticsView(model: session) }
                .tabItem { Label(appStrings.current.tabDiagnostics, systemImage: "gauge") }
                .accessibilityIdentifier("tab.diagnostics")
        }
        .tint(FlexTheme.primary)
        .preferredColorScheme(.dark)
        .environmentObject(appStrings)
        .onChange(of: appStrings.current.tabLive) { _ in
            // Propagate language changes to the session model so error strings
            // produced inside the model are always in the selected language.
            session.uiStrings = appStrings.current
        }
    }
}
