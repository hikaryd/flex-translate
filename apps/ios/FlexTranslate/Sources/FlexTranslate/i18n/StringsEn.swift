import Foundation

/// English UI-chrome copy. Faithful translations of the Russian originals. Mirrors Android StringsEn.
struct StringsEn: Strings {

    // --- App shell -------------------------------------------------------------------------------
    var tabLive: String { "Live" }
    var tabLanguages: String { "Languages" }
    var tabModels: String { "Models" }
    var tabCloud: String { "Cloud" }
    var tabDiagnostics: String { "Diagnostics" }
    var demoBanner: String { "Demo · launch support not claimed" }

    // --- Live screen -----------------------------------------------------------------------------
    var modeOffline: String { "offline" }
    var micReady: String { "microphone ready" }
    func missingPackBadge(_ packId: String) -> String { "no pack: \(packId)" }
    var offlineTranslationNotClaimed: String { "offline translation not claimed" }
    var cloudDisabledBadge: String { "cloud disabled" }
    var micLevelTitle: String { "Microphone level" }
    var speech: String { "speech" }
    var silence: String { "silence" }
    var micIdleHint: String { "Idle — tap \u{201C}Listen\u{201D} to see the real level." }
    func asrNotClaimedHint(_ languageLabel: String) -> String {
        "ASR support is not claimed yet — the transcript will appear after the local " +
        "model for \(languageLabel) is downloaded (see Models)."
    }
    func listeningHint(_ languageLabel: String) -> String {
        "Listening\u{2026} speak in \(languageLabel). " +
        "Text is recognized locally (demo, quality not verified)."
    }
    func readyToListenHint(_ languageLabel: String) -> String {
        "Tap \u{201C}Listen\u{201D} \u{2014} the local \(languageLabel) model is ready."
    }
    var translationTitle: String { "Translation" }
    var translating: String { "translating\u{2026}" }
    var translatingCloud: String { "Translating in the cloud\u{2026}" }
    var translatingLocal: String { "Translating locally\u{2026}" }
    func translationPendingCloud(_ modelName: String) -> String {
        "The translation will appear after a phrase is recognized " +
        "(cloud \(modelName) \u{2014} requires consent and a backend, see Cloud)."
    }
    func translationPendingLocal(_ modelName: String) -> String {
        "The translation will appear after a phrase is recognized " +
        "(model \(modelName), demo, quality not verified)."
    }
    var stop: String { "Stop" }
    var listen: String { "Listen" }
    var grantMic: String { "Allow microphone" }
    func missingPackHint(_ packId: String) -> String { "No offline pack: \(packId) (see Models)." }
    var demoRecognizing: String { "Recognizing test audio\u{2026}" }
    func demoRecognizeButton(_ languageCode: String) -> String {
        "Demo: recognize test \(languageCode) audio"
    }

    // --- Languages screen ------------------------------------------------------------------------
    var languagePairTitle: String { "Language pair" }
    var sourceLabel: String { "Source" }
    var targetLabel: String { "Target" }
    var swapLanguagesDescription: String { "Swap languages" }
    func pairSupportTitle(_ pairLabel: String) -> String { "Support for \(pairLabel)" }
    var offlineAsrAdapterReady: String { "offline ASR: adapter ready (demo)" }
    var offlineTranslationNotClaimedLong: String {
        "offline translation: not claimed (needs benchmark + model)"
    }
    var supportFromBenchmarksFooter: String {
        "Support is derived from benchmark evidence, not from intentions."
    }
    var mtModelPickerTitle: String { "Translation model" }
    var mtModelPickerHint: String {
        "Choose a model by quality/speed. The choice is used in the dialogue."
    }
    var selected: String { "selected" }
    var executionCloud: String { "cloud" }
    var executionOnDevice: String { "on device" }
    func qualityBadge(_ label: String) -> String { "quality: \(label)" }
    func speedBadge(_ label: String) -> String { "speed: \(label)" }
    var cloudCallInWs5: String { "real call in WS5 (needs consent + network)" }
    var mtModelInstalledLocal: String { "model installed \u{2014} translation is local" }
    var mtModelNotInstalled: String { "model not installed (see Models)" }
    var mtModelOptional: String { "optional \u{2014} pack not added yet" }

    // --- MT routing mode -------------------------------------------------------------------------
    var mtRoutingModeTitle: String { "Routing mode" }
    var mtRoutingModeAuto: String { "Auto" }
    var mtRoutingModeAutoHint: String {
        "Auto: Gemini Flash when online and consented, otherwise the local model."
    }
    var mtRoutingModeOnDevice: String { "Device only" }
    var mtRoutingModeCloud: String { "Cloud only" }
    var engineBadgeGemini: String { "Gemini Flash" }
    func engineBadgeOnDevice(_ modelId: String) -> String { "on device \u{00B7} \(modelId)" }

    // --- Models screen ---------------------------------------------------------------------------
    var offlinePacksTitle: String { "Offline packs" }
    var offlinePacksHeader: String {
        "Model weights are not bundled in the build (license/size) \u{2014} they are downloaded in the app " +
        "over the network with a checksum check. Installed packs show their real " +
        "on-device size. Support is not claimed without benchmark evidence."
    }
    var cancel: String { "Cancel" }
    func downloadingFile(_ file: String) -> String { "Downloading: \(file)" }
    var installed: String { "installed" }
    var delete: String { "Delete" }
    var statusError: String { "error" }
    var statusCancelled: String { "cancelled" }
    var statusNotInstalled: String { "not installed" }
    var retry: String { "Retry" }
    var download: String { "Download" }
    var installedStatusLine: String {
        "Ready for local recognition (demo, quality not verified)."
    }
    func downloadFailedLine(_ message: String) -> String {
        "Error: \(message). The file is verified with SHA-256; retry will resume the download."
    }
    var downloadCancelledLine: String {
        "Download cancelled. The partial file is kept \u{2014} retry will resume the download."
    }
    var sourceNotConfiguredLine: String {
        "A download source is not configured for this pack yet."
    }
    var onlineOnlyLine: String {
        "Available online only. The checksum is verified after the download."
    }
    var downloadsOverNetworkLine: String {
        "Downloaded over the network, verified with SHA-256, then available offline."
    }
    var gemmaTermsLink: String { "Gemma Terms of Use and Prohibited Use Policy" }
    var sizeUnknown: String { "size \u{2014}" }

    // --- Cloud screen ----------------------------------------------------------------------------
    var cloudTitle: String { "Cloud" }
    var cloudHeader: String {
        "Cloud is off by default \u{00B7} no silent fallback \u{00B7} no embedded " +
        "API keys (backend ephemeral tokens)."
    }
    var hideDisclosure: String { "Hide data disclosure" }
    var showDisclosure: String { "Show data disclosure" }
    var acceptDisclosure: String { "Accept disclosure" }
    var backendEndpointLabel: String { "Backend endpoint (no Gemini key)" }
    var backendEndpointPlaceholder: String { "https://flex-backend.example.com" }
    var backendMediationHint: String {
        "Translation goes through your backend (mediation): it keeps the Gemini key on the server and " +
        "returns text only. Until an endpoint is set, cloud translation is honestly blocked."
    }
    var readyToStart: String { "ready to start" }
    func disabledMissing(_ missing: String) -> String { "disabled \u{00B7} missing: \(missing)" }
    var missingConsent: String { "consent" }
    var missingDisclosure: String { "disclosure" }
    var missingOnline: String { "online" }
    var missingEphemeralToken: String { "ephemeral token" }
    var interfaceLanguageTitle: String { "Interface language" }
    var interfaceLanguageHint: String {
        "Switch the interface language instantly. Does not affect recognition or translation languages."
    }

    // --- Diagnostics screen ----------------------------------------------------------------------
    var captureSectionTitle: String { "Audio capture" }
    var pipelineSectionTitle: String { "Pipeline" }
    var buildDeviceSectionTitle: String { "Build / device" }
    var telemetrySectionTitle: String { "Telemetry" }
    var asrSupportNotClaimed: String { "not claimed" }
    var telemetryPendingHint: String { "Events will appear after telemetry is enabled (WS6)." }
    var telemetryNoEventsYet: String { "No events yet \u{2014} start a capture session." }

    // --- LiveSessionModel reasons ----------------------------------------------------------------
    func mtEngineUnavailable(_ modelName: String) -> String {
        "MT engine unavailable for \(modelName)"
    }
    func mtModelNotInstalledReason(_ modelId: String) -> String {
        "MT model \(modelId) is not installed (see Models)"
    }

    // --- Dialogue / conversation log -------------------------------------------------------------
    var dialogueClearButton: String { "Clear dialogue" }
    var dialogueEmptyHint: String {
        "Start speaking \u{2014} turns will appear here. Use the swap button to change who is speaking."
    }
    func dialogueSpeakingLabel(_ languageLabel: String) -> String { languageLabel }
    var dialoguePendingTranslation: String { "translating\u{2026}" }
}
