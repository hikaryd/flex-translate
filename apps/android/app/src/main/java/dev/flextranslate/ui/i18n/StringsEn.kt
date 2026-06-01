package dev.flextranslate.ui.i18n

import dev.flextranslate.foundation.GeminiFlashTranslationProvider

/** English UI-chrome copy. Faithful translations of the Russian originals. */
object StringsEn : Strings {

    // --- App shell -------------------------------------------------------------------------------
    override val tabLive = "Live"
    override val tabLanguages = "Languages"
    override val tabModels = "Models"
    override val tabCloud = "Cloud"
    override val tabDiagnostics = "Diagnostics"
    override val demoBanner = "Demo · launch support not claimed"

    // --- Live screen -----------------------------------------------------------------------------
    override val modeOffline = "offline"
    override val micReady = "microphone ready"
    override fun missingPackBadge(packId: String) = "no pack: $packId"
    override val offlineTranslationNotClaimed = "offline translation not claimed"
    override val cloudDisabledBadge = "cloud disabled"
    override val micLevelTitle = "Microphone level"
    override val speech = "speech"
    override val silence = "silence"
    override val micIdleHint = "Idle — tap “Listen” to see the real level."
    override fun asrNotClaimedHint(languageLabel: String) =
        "ASR support is not claimed yet — the transcript will appear after the local " +
            "model for $languageLabel is downloaded (see Models)."
    override fun listeningHint(languageLabel: String) =
        "Listening… speak in $languageLabel. " +
            "Text is recognized locally (demo, quality not verified)."
    override fun readyToListenHint(languageLabel: String) =
        "Tap “Listen” — the local $languageLabel model is ready."
    override val translationTitle = "Translation"
    override val translating = "translating…"
    override val translatingCloud = "Translating in the cloud…"
    override val translatingLocal = "Translating locally…"
    override fun translationPendingCloud(modelName: String) =
        "The translation will appear after a phrase is recognized " +
            "(cloud $modelName — requires consent and a backend, see Cloud)."
    override fun translationPendingLocal(modelName: String) =
        "The translation will appear after a phrase is recognized " +
            "(model $modelName, demo, quality not verified)."
    override val stop = "Stop"
    override val listen = "Listen"
    override val grantMic = "Allow microphone"
    override fun missingPackHint(packId: String) = "No offline pack: $packId (see Models)."
    override val demoRecognizing = "Recognizing test audio…"
    override fun demoRecognizeButton(languageCode: String) =
        "Demo: recognize test $languageCode audio"

    // --- Languages screen ------------------------------------------------------------------------
    override val languagePairTitle = "Language pair"
    override val sourceLabel = "Source"
    override val targetLabel = "Target"
    override val swapLanguagesDescription = "Swap languages"
    override fun pairSupportTitle(pairLabel: String) = "Support for $pairLabel"
    override val offlineAsrAdapterReady = "offline ASR: adapter ready (demo)"
    override val offlineTranslationNotClaimedLong = "offline translation: not claimed (needs benchmark + model)"
    override val supportFromBenchmarksFooter =
        "Support is derived from benchmark evidence, not from intentions."
    override val mtModelPickerTitle = "Translation model"
    override val mtModelPickerHint = "Choose a model by quality/speed. The choice is used in the dialogue."
    override val selected = "selected"
    override val executionCloud = "cloud"
    override val executionOnDevice = "on device"
    override fun qualityBadge(label: String) = "quality: $label"
    override fun speedBadge(label: String) = "speed: $label"
    override val cloudCallInWs5 = "real call in WS5 (needs consent + network)"
    override val mtModelInstalledLocal = "model installed — translation is local"
    override val mtModelNotInstalled = "model not installed (see Models)"
    override val mtModelOptional = "optional — pack not added yet"

    // --- Models screen ---------------------------------------------------------------------------
    override val offlinePacksTitle = "Offline packs"
    override val offlinePacksHeader =
        "Model weights are not bundled in the build (license/size) — they are downloaded in the app " +
            "over the network with a checksum check. Installed packs show their real " +
            "on-device size. Support is not claimed without benchmark evidence."
    override val cancel = "Cancel"
    override fun downloadingFile(file: String) = "Downloading: $file"
    override val installed = "installed"
    override val delete = "Delete"
    override val statusError = "error"
    override val statusCancelled = "cancelled"
    override val statusNotInstalled = "not installed"
    override val retry = "Retry"
    override val download = "Download"
    override val installedStatusLine = "Ready for local recognition (demo, quality not verified)."
    override fun downloadFailedLine(message: String) =
        "Error: $message. The file is verified with SHA-256; retry will resume the download."
    override val downloadCancelledLine =
        "Download cancelled. The partial file is kept — retry will resume the download."
    override val sourceNotConfiguredLine = "A download source is not configured for this pack yet."
    override val onlineOnlyLine = "Available online only. The checksum is verified after the download."
    override val downloadsOverNetworkLine = "Downloaded over the network, verified with SHA-256, then available offline."
    override val gemmaTermsLink = "Gemma Terms of Use and Prohibited Use Policy"
    override val sizeUnknown = "size —"

    // --- Cloud screen ----------------------------------------------------------------------------
    override val cloudTitle = "Cloud"
    override val cloudHeader =
        "Cloud is off by default · no silent fallback · no embedded " +
            "API keys (backend ephemeral tokens)."
    override val hideDisclosure = "Hide data disclosure"
    override val showDisclosure = "Show data disclosure"
    override val acceptDisclosure = "Accept disclosure"
    override val backendEndpointLabel = "Backend endpoint (no Gemini key)"
    override val backendEndpointPlaceholder = "https://flex-backend.example.com"
    override val backendMediationHint =
        "Translation goes through your backend (mediation): it keeps the Gemini key on the server and " +
            "returns text only. Until an endpoint is set, cloud translation is honestly blocked."
    override val readyToStart = "ready to start"
    override fun disabledMissing(missing: String) = "disabled · missing: $missing"
    override val missingConsent = "consent"
    override val missingDisclosure = "disclosure"
    override val missingOnline = "online"
    override val missingEphemeralToken = "ephemeral token"
    override val credentialModeLabel = "Connection mode"
    override val credentialModeBackend = "Backend"
    override val credentialModeOwnKey = "Own key"
    override val ownKeyInputLabel = "Gemini API key"
    override val ownKeyInputPlaceholder = "AIza…"
    override val ownKeySaveButton = "Save"
    override val ownKeyClearButton = "Clear key"
    override val ownKeyStoredHint = "Key saved (encrypted on device)"
    override val ownKeyGeoRestrictionNote =
        "Note: direct Gemini access is geo-restricted in some regions (RU, etc.). " +
            "If you see a geo-block error, use backend mode or a VPN."
    override fun cloudProviderTitle(providerId: String) = CloudCopyEn.copy[providerId]?.title
    override fun cloudProviderRole(providerId: String) = CloudCopyEn.copy[providerId]?.role
    override fun cloudProviderDisclosure(providerId: String) = CloudCopyEn.copy[providerId]?.disclosure
    override val interfaceLanguageTitle = "Interface language"
    override val interfaceLanguageHint =
        "Switch the interface language instantly. Does not affect recognition or translation languages."

    // --- Diagnostics screen ----------------------------------------------------------------------
    override val captureSectionTitle = "Audio capture"
    override val pipelineSectionTitle = "Pipeline"
    override val buildDeviceSectionTitle = "Build / device"
    override val telemetrySectionTitle = "Telemetry"
    override val asrSupportNotClaimed = "not claimed"
    override val telemetryPendingHint = "Events will appear after telemetry is enabled (WS6)."
    override val telemetryNoEventsYet = "No events yet — start a capture session."

    // --- LiveSessionState reasons ----------------------------------------------------------------
    override fun mtEngineUnavailable(modelName: String) = "MT engine unavailable for $modelName"
    override fun mtModelNotInstalledReason(modelId: String) =
        "MT model $modelId is not installed (see Models)"

    // --- Dialogue / conversation log -------------------------------------------------------------
    override val dialogueClearButton = "Clear dialogue"
    override val dialogueEmptyHint =
        "Start speaking — turns will appear here. Use the swap button to change who is speaking."
    override fun dialogueSpeakingLabel(languageLabel: String) = "$languageLabel"
    override val dialoguePendingTranslation = "translating…"
}

/** Holder for the per-provider cloud copy (title/role/disclosure) in English. */
private object CloudCopyEn {
    val copy: Map<String, CloudProviderCopy> = mapOf(
        GeminiFlashTranslationProvider.PROVIDER_ID to CloudProviderCopy(
            title = "Gemini Flash · cloud translation (MT)",
            role = "Highest translation quality via the cloud. Only the text of the finalized phrase — " +
                "audio is not sent in this mode.",
            disclosure = "What leaves the device: only the text of the recognized (finalized) phrase " +
                "of the current utterance — audio is NOT sent for the text MT mode.\n" +
                "Where: to our backend, which forwards the request to the Google Gemini API. " +
                "The Gemini key is stored on the server only — there are no embedded API keys in the app.\n" +
                "Retention: per the provider's data-processing policy. The app does not keep " +
                "transcripts beyond the session unless you enable history yourself.\n" +
                "Cloud is off by default and never silently replaces offline translation: if the cloud " +
                "is unavailable an honest reason is shown, and the offline model keeps working.",
        ),
        "cloud-stt-recognition-fallback" to CloudProviderCopy(
            title = "Cloud STT · recognition fallback",
            role = "Cloud recognition as a fallback when the offline model can't keep up.",
            disclosure = "Audio is sent to the server only when explicitly enabled. Consent to processing " +
                "and the data-retention policy must be confirmed separately.",
        ),
        "gemini-live-assistant" to CloudProviderCopy(
            title = "Gemini Live · realtime assistant",
            role = "Realtime assistant over live audio (low latency).",
            disclosure = "Real-time audio streaming. Requires consent, an accepted disclosure, " +
                "and an ephemeral token from the backend.",
        ),
        "gemini-batch-audio-enrichment" to CloudProviderCopy(
            title = "Gemini batch · async enrichment",
            role = "Asynchronous batch enrichment of recorded fragments.",
            disclosure = "Fragments are sent in batches after the session. Data retention is described " +
                "in the provider's policy; consent is required.",
        ),
    )
}
