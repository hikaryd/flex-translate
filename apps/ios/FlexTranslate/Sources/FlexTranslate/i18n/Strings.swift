import SwiftUI

/// The complete catalog of user-facing UI-chrome strings, one member per string.
/// Two implementations — StringsRu and StringsEn — provide the Russian and English copy;
/// the active one is supplied through LocalStrings at the app root and switched at runtime
/// by the in-app RU/EN toggle.
///
/// Scope: this localizes the SHELL/UI chrome only. Model-id / technical diagnostic tokens
/// are NOT part of this catalog — they are language-neutral technical identifiers and stay
/// verbatim. The no-false-claims gating semantics are unchanged; only the human-readable
/// copy is translated.
///
/// Plain strings are computed vars; strings that interpolate runtime data are funcs so the
/// catalog stays exhaustive and the RU/EN parity test can compare a fixed key set.
protocol Strings: Sendable {

    // --- App shell -------------------------------------------------------------------------------
    var tabLive: String { get }
    var tabLanguages: String { get }
    var tabModels: String { get }
    var tabCloud: String { get }
    var tabDiagnostics: String { get }
    var demoBanner: String { get }

    // --- Live screen -----------------------------------------------------------------------------
    var modeOffline: String { get }
    var micReady: String { get }
    func missingPackBadge(_ packId: String) -> String
    var offlineTranslationNotClaimed: String { get }
    var cloudDisabledBadge: String { get }
    var micLevelTitle: String { get }
    var speech: String { get }
    var silence: String { get }
    var micIdleHint: String { get }
    func asrNotClaimedHint(_ languageLabel: String) -> String
    func listeningHint(_ languageLabel: String) -> String
    func readyToListenHint(_ languageLabel: String) -> String
    var translationTitle: String { get }
    var translating: String { get }
    var translatingCloud: String { get }
    var translatingLocal: String { get }
    func translationPendingCloud(_ modelName: String) -> String
    func translationPendingLocal(_ modelName: String) -> String
    var stop: String { get }
    var listen: String { get }
    var grantMic: String { get }
    func missingPackHint(_ packId: String) -> String
    var demoRecognizing: String { get }
    func demoRecognizeButton(_ languageCode: String) -> String

    // --- Languages screen ------------------------------------------------------------------------
    var languagePairTitle: String { get }
    var sourceLabel: String { get }
    var targetLabel: String { get }
    var swapLanguagesDescription: String { get }
    func pairSupportTitle(_ pairLabel: String) -> String
    var offlineAsrAdapterReady: String { get }
    var offlineTranslationNotClaimedLong: String { get }
    var supportFromBenchmarksFooter: String { get }
    var mtModelPickerTitle: String { get }
    var mtModelPickerHint: String { get }
    var selected: String { get }
    var executionCloud: String { get }
    var executionOnDevice: String { get }
    func qualityBadge(_ label: String) -> String
    func speedBadge(_ label: String) -> String
    var cloudCallInWs5: String { get }
    var mtModelInstalledLocal: String { get }
    var mtModelNotInstalled: String { get }
    var mtModelOptional: String { get }

    // --- MT routing mode (AUTO / ON_DEVICE / CLOUD) ----------------------------------------------
    var mtRoutingModeTitle: String { get }
    var mtRoutingModeAuto: String { get }
    var mtRoutingModeAutoHint: String { get }
    var mtRoutingModeOnDevice: String { get }
    var mtRoutingModeCloud: String { get }
    var engineBadgeGemini: String { get }
    func engineBadgeOnDevice(_ modelId: String) -> String

    // --- Models screen ---------------------------------------------------------------------------
    var offlinePacksTitle: String { get }
    var offlinePacksHeader: String { get }
    var cancel: String { get }
    func downloadingFile(_ file: String) -> String
    var installed: String { get }
    var delete: String { get }
    var statusError: String { get }
    var statusCancelled: String { get }
    var statusNotInstalled: String { get }
    var retry: String { get }
    var download: String { get }
    var installedStatusLine: String { get }
    func downloadFailedLine(_ message: String) -> String
    var downloadCancelledLine: String { get }
    var sourceNotConfiguredLine: String { get }
    var onlineOnlyLine: String { get }
    var downloadsOverNetworkLine: String { get }
    var gemmaTermsLink: String { get }
    var sizeUnknown: String { get }

    // --- Cloud screen ----------------------------------------------------------------------------
    var cloudTitle: String { get }
    var cloudHeader: String { get }
    var hideDisclosure: String { get }
    var showDisclosure: String { get }
    var acceptDisclosure: String { get }
    var backendEndpointLabel: String { get }
    var backendEndpointPlaceholder: String { get }
    var backendMediationHint: String { get }
    var readyToStart: String { get }
    func disabledMissing(_ missing: String) -> String
    var missingConsent: String { get }
    var missingDisclosure: String { get }
    var missingOnline: String { get }
    var missingEphemeralToken: String { get }
    var interfaceLanguageTitle: String { get }
    var interfaceLanguageHint: String { get }

    // --- Diagnostics screen ----------------------------------------------------------------------
    var captureSectionTitle: String { get }
    var pipelineSectionTitle: String { get }
    var buildDeviceSectionTitle: String { get }
    var telemetrySectionTitle: String { get }
    var asrSupportNotClaimed: String { get }
    var telemetryPendingHint: String { get }
    var telemetryNoEventsYet: String { get }

    // --- LiveSessionModel translation reasons (surface to the Live screen) -----------------------
    func mtEngineUnavailable(_ modelName: String) -> String
    func mtModelNotInstalledReason(_ modelId: String) -> String

    // --- Dialogue / conversation log -------------------------------------------------------------
    var dialogueClearButton: String { get }
    var dialogueEmptyHint: String { get }
    func dialogueSpeakingLabel(_ languageLabel: String) -> String
    var dialoguePendingTranslation: String { get }
}

/// The active Strings for the current environment. Defaults to StringsRu so any view
/// read before the root provider is set still renders. The app root always overrides it
/// with the persisted/selected language. @EnvironmentObject because the whole tree needs
/// to recompose on language switch — a simple @Environment(\.locale) trick won't do.
class AppStrings: ObservableObject {
    @Published var current: any Strings

    init(language: AppLanguage = AppLanguageStore.shared.load()) {
        self.current = stringsFor(language)
    }

    func switchTo(_ language: AppLanguage) {
        AppLanguageStore.shared.save(language)
        current = stringsFor(language)
    }
}

/// Resolve the Strings catalog for an AppLanguage.
func stringsFor(_ language: AppLanguage) -> any Strings {
    switch language {
    case .ru: return StringsRu()
    case .en: return StringsEn()
    }
}

// Note: direct environment injection of `any Strings` is not used in this codebase —
// views access strings via @EnvironmentObject AppStrings instead. The key is retained
// for potential future use but the defaultValue avoids the Sendable static-storage issue
// by using a nonisolated(unsafe) workaround that is safe because StringsRu is a value type.
