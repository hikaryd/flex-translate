package dev.flextranslate.ui.i18n

/**
 * Localized copy for a single cloud-provider card: its title, the short role line, and the full
 * data-disclosure paragraph. Sourced from the active [Strings] catalog keyed by provider id, so the
 * Cloud screen renders RU/EN consistently with the rest of the chrome.
 */
data class CloudProviderCopy(
    val title: String,
    val role: String,
    val disclosure: String,
)
