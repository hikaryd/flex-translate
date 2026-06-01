package dev.flextranslate.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.CloudOptInState
import dev.flextranslate.foundation.GeminiFlashTranslationProvider
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard
import dev.flextranslate.ui.i18n.AppLanguage
import dev.flextranslate.ui.i18n.LocalStrings

/**
 * Облако / Cloud (Settings) — opt-in cloud, default OFF, honest disclosure. No silent fallback,
 * no embedded API keys; cloud calls require backend ephemeral tokens.
 *
 * Also hosts the in-app interface-language switcher ([LanguageSwitcherCard]), so the user can
 * flip between RU and EN without leaving the app.
 */
@Composable
fun CloudScreen(
    session: LiveSessionState,
    selectedLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val states by session.cloudStates
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Interface-language switcher — always at the top of the Cloud/Settings surface.
        LanguageSwitcherCard(selectedLanguage = selectedLanguage, onLanguageChange = onLanguageChange)

        SectionCard(radius = 12, title = s.cloudTitle) {
            SecondaryText(s.cloudHeader)
        }
        states.forEach { state ->
            val isCloudMt = state.providerId == GeminiFlashTranslationProvider.PROVIDER_ID
            CloudProviderCard(
                providerId = state.providerId,
                state = state,
                onConsentChange = { session.setUserConsent(state.providerId, it) },
                onDisclosureChange = { session.setDisclosureAccepted(state.providerId, it) },
                // Only the Gemini Flash MT tier exposes a backend-endpoint field + model line.
                backendConfig = if (isCloudMt) {
                    BackendConfig(
                        modelId = session.geminiConfig.modelId,
                        endpoint = session.geminiConfig.backendBaseUrl,
                        onEndpointChange = session::setGeminiBackendEndpoint,
                    )
                } else {
                    null
                },
            )
        }
    }
}

/**
 * Interface-language toggle card. Shows the current language name and a segmented RU / EN row so
 * the user can switch instantly. Placed at the top of the Cloud/Settings tab.
 */
@Composable
private fun LanguageSwitcherCard(
    selectedLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val s = LocalStrings.current
    SectionCard(radius = 12, title = s.interfaceLanguageTitle) {
        SecondaryText(s.interfaceLanguageHint)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { lang ->
                val isSelected = lang == selectedLanguage
                Badge(
                    text = lang.nativeLabel,
                    tone = if (isSelected) BadgeTone.ACCENT else BadgeTone.NEUTRAL,
                    modifier = Modifier.clickable { onLanguageChange(lang) },
                )
            }
        }
    }
}

/** Backend-mediation fields surfaced only on the Gemini Flash MT card. No API key — endpoint only. */
private data class BackendConfig(
    val modelId: String,
    val endpoint: String,
    val onEndpointChange: (String) -> Unit,
)

@Composable
private fun CloudProviderCard(
    providerId: String,
    state: CloudOptInState,
    onConsentChange: (Boolean) -> Unit,
    onDisclosureChange: (Boolean) -> Unit,
    backendConfig: BackendConfig? = null,
) {
    val s = LocalStrings.current
    // Resolve localised copy from the active Strings catalog; fall back to the provider id as title
    // when no copy is registered (future providers surfaced before a translation is added).
    val title = s.cloudProviderTitle(providerId) ?: providerId
    val role = s.cloudProviderRole(providerId).orEmpty()
    val disclosure = s.cloudProviderDisclosure(providerId).orEmpty()

    var disclosureExpanded by remember { mutableStateOf(false) }
    val nowEpochMs = remember { System.currentTimeMillis() }
    SectionCard(radius = 12) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // Toggle is the user-consent intent. Default OFF; enabling never auto-starts a call —
            // canStart still requires disclosure + online + ephemeral token.
            Switch(checked = state.userConsented, onCheckedChange = onConsentChange)
        }
        SecondaryText(role)

        if (backendConfig != null) {
            BackendMediationFields(backendConfig)
        }

        Text(
            text = if (disclosureExpanded) s.hideDisclosure else s.showDisclosure,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { disclosureExpanded = !disclosureExpanded },
        )
        if (disclosureExpanded) {
            SecondaryText(disclosure)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.acceptDisclosure, style = MaterialTheme.typography.bodySmall)
                Switch(checked = state.disclosureAccepted, onCheckedChange = onDisclosureChange)
            }
        }

        StateLine(state, nowEpochMs)
    }
}

/**
 * Backend-mediation config for the Gemini Flash MT tier: the resolved model id (read-only,
 * config-driven) and the operator-run backend base URL. There is NO API-key field — the app never
 * holds a Gemini key; the backend injects it server-side.
 */
@Composable
private fun BackendMediationFields(config: BackendConfig) {
    val s = LocalStrings.current
    Badge(text = "model: ${config.modelId}", tone = BadgeTone.NEUTRAL, mono = true)
    // Local edit buffer; committed to the session on each change so the gate sees the new endpoint.
    var endpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
    OutlinedTextField(
        value = endpoint,
        onValueChange = {
            endpoint = it
            config.onEndpointChange(it)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(s.backendEndpointLabel) },
        placeholder = { Text(s.backendEndpointPlaceholder) },
    )
    SecondaryText(s.backendMediationHint)
}

@Composable
private fun StateLine(state: CloudOptInState, nowEpochMs: Long) {
    val s = LocalStrings.current
    if (state.canStart(nowEpochMs)) {
        Badge(text = s.readyToStart, tone = BadgeTone.GREEN)
        return
    }
    val missing = buildList {
        if (!state.userConsented) add(s.missingConsent)
        if (!state.disclosureAccepted) add(s.missingDisclosure)
        if (state.networkState != "online") add(s.missingOnline)
        if (state.credential?.isEphemeral(nowEpochMs) != true) add(s.missingEphemeralToken)
    }
    Badge(text = s.disabledMissing(missing.joinToString(", ")), tone = BadgeTone.NEUTRAL)
}
