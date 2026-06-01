package dev.flextranslate.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The complete catalog of user-facing UI-chrome strings, one member per string. Two
 * implementations — [StringsRu] and [StringsEn] — provide the Russian and English copy; the active
 * one is supplied through [LocalStrings] at the app root and switched at runtime by the in-app
 * RU/EN toggle.
 *
 * Scope: this localizes the SHELL/UI chrome only. Model-id / technical diagnostic tokens
 * (`sampleRateHz`, `peak`, model display names from the candidate registries, etc.) are NOT part of
 * this catalog — they are language-neutral technical identifiers and stay verbatim. The
 * no-false-claims gating semantics are unchanged; only the human-readable copy is translated.
 *
 * Plain strings are `val`s; strings that interpolate runtime data are functions so the catalog
 * stays exhaustive and the RU/EN parity test can compare a fixed key set.
 */
interface Strings {

    // --- App shell (AppScaffold) -----------------------------------------------------------------
    val tabLive: String
    val tabLanguages: String
    val tabModels: String
    val tabCloud: String
    val tabDiagnostics: String
    val demoBanner: String

    // --- Live screen -----------------------------------------------------------------------------
    val modeOffline: String
    val micReady: String
    fun missingPackBadge(packId: String): String
    val offlineTranslationNotClaimed: String
    val cloudDisabledBadge: String
    val micLevelTitle: String
    val speech: String
    val silence: String
    val micIdleHint: String
    fun asrNotClaimedHint(languageLabel: String): String
    fun listeningHint(languageLabel: String): String
    fun readyToListenHint(languageLabel: String): String
    val translationTitle: String
    val translating: String
    val translatingCloud: String
    val translatingLocal: String
    fun translationPendingCloud(modelName: String): String
    fun translationPendingLocal(modelName: String): String
    val stop: String
    val listen: String
    val grantMic: String
    fun missingPackHint(packId: String): String
    val demoRecognizing: String
    fun demoRecognizeButton(languageCode: String): String

    // --- Languages screen ------------------------------------------------------------------------
    val languagePairTitle: String
    val sourceLabel: String
    val targetLabel: String
    val swapLanguagesDescription: String
    fun pairSupportTitle(pairLabel: String): String
    val offlineAsrAdapterReady: String
    val offlineTranslationNotClaimedLong: String
    val supportFromBenchmarksFooter: String
    val mtModelPickerTitle: String
    val mtModelPickerHint: String
    val selected: String
    val executionCloud: String
    val executionOnDevice: String
    fun qualityBadge(label: String): String
    fun speedBadge(label: String): String
    val cloudCallInWs5: String
    val mtModelInstalledLocal: String
    val mtModelNotInstalled: String
    val mtModelOptional: String

    // --- Models screen ---------------------------------------------------------------------------
    val offlinePacksTitle: String
    val offlinePacksHeader: String
    val cancel: String
    fun downloadingFile(file: String): String
    val installed: String
    val delete: String
    val statusError: String
    val statusCancelled: String
    val statusNotInstalled: String
    val retry: String
    val download: String
    val installedStatusLine: String
    fun downloadFailedLine(message: String): String
    val downloadCancelledLine: String
    val sourceNotConfiguredLine: String
    val onlineOnlyLine: String
    val downloadsOverNetworkLine: String
    val gemmaTermsLink: String
    val sizeUnknown: String

    // --- Cloud screen ----------------------------------------------------------------------------
    val cloudTitle: String
    val cloudHeader: String
    val hideDisclosure: String
    val showDisclosure: String
    val acceptDisclosure: String
    val backendEndpointLabel: String
    val backendEndpointPlaceholder: String
    val backendMediationHint: String
    val readyToStart: String
    fun disabledMissing(missing: String): String
    val missingConsent: String
    val missingDisclosure: String
    val missingOnline: String
    val missingEphemeralToken: String
    // Cloud provider copy, keyed by provider id (titles/roles/disclosures).
    fun cloudProviderTitle(providerId: String): String?
    fun cloudProviderRole(providerId: String): String?
    fun cloudProviderDisclosure(providerId: String): String?
    // The in-app language switcher block.
    val interfaceLanguageTitle: String
    val interfaceLanguageHint: String

    // --- Diagnostics screen ----------------------------------------------------------------------
    val captureSectionTitle: String
    val pipelineSectionTitle: String
    val buildDeviceSectionTitle: String
    val telemetrySectionTitle: String
    val asrSupportNotClaimed: String
    val telemetryPendingHint: String

    // --- LiveSessionState translation reasons (surface to the Live screen) -----------------------
    fun mtEngineUnavailable(modelName: String): String
    fun mtModelNotInstalledReason(modelId: String): String
}

/**
 * The active [Strings] for the current composition. Defaults to [StringsRu] so any composable
 * read before the root provider is set still renders (the app root always overrides it with the
 * persisted/selected language). Static because the value changes rarely (only on a language flip).
 */
val LocalStrings = staticCompositionLocalOf<Strings> { StringsRu }

/** Resolve the [Strings] catalog for an [AppLanguage]. */
fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.RU -> StringsRu
    AppLanguage.EN -> StringsEn
}
