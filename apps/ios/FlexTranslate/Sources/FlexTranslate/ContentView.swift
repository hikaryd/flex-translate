import SwiftUI

// Корневая навигация — TabView на 5 вкладок, каждая в своём NavigationStack.
// AppStrings создаём тут и прокидываем как EnvironmentObject, чтобы при смене
// языка все дочерние вью пересобрались на новом.
struct ContentView: View {
    @StateObject private var session = LiveSessionModel()
    @StateObject private var appStrings = AppStrings()

    var body: some View {
        TabView {
            NavigationStack { LiveView(model: session) }
                .tabItem { Label(appStrings.current.tabLive, systemImage: "waveform") }
                .accessibilityIdentifier("tab.live")
                .onAppear {
                    // CI/демо: запуск с -MT_TEST сам прогоняет offline-MT для сбора доказательств.
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
            // Прокидываем смену языка в session-модель, чтобы её строки ошибок
            // всегда были на выбранном языке.
            session.uiStrings = appStrings.current
        }
    }
}
