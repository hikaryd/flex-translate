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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.CloudOptInState
import dev.flextranslate.foundation.GeminiCredentialMode
import dev.flextranslate.foundation.GeminiFlashTranslationProvider
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard
import dev.flextranslate.ui.i18n.AppLanguage
import dev.flextranslate.ui.i18n.LocalStrings

/**
 * Облако (Настройки) — облако по согласию, по умолчанию ВЫКЛ, с честным раскрытием. Без тихого
 * фолбэка и без вшитых API-ключей; облачные вызовы требуют эфемерных токенов от бэкенда.
 *
 * Заодно держит переключатель языка интерфейса ([LanguageSwitcherCard]), чтобы менять RU/EN
 * не выходя из приложения.
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
        // Переключатель языка интерфейса — всегда вверху экрана Облако/Настройки.
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
                // Только у тира Gemini Flash MT есть выбор режима credential и поля бэкенда/ключа.
                geminiMtConfig = if (isCloudMt) {
                    GeminiMtConfig(
                        modelId = session.geminiConfig.modelId,
                        credentialMode = session.geminiConfig.credentialMode,
                        onCredentialModeChange = session::setGeminiCredentialMode,
                        endpoint = session.geminiConfig.backendBaseUrl,
                        onEndpointChange = session::setGeminiBackendEndpoint,
                        ownKeyStored = session.geminiOwnKeyStored,
                        onSaveOwnKey = session::saveGeminiOwnKey,
                        onClearOwnKey = session::clearGeminiOwnKey,
                    )
                } else {
                    null
                },
            )
        }
    }
}

/**
 * Карточка переключения языка интерфейса. Показывает текущий язык и сегментированный ряд RU / EN
 * для мгновенного переключения. Стоит вверху вкладки Облако/Настройки.
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

/**
 * Вся конфигурация Gemini Flash MT, что показываем на карточке облачного провайдера.
 * Несёт и endpoint бэкенд-посредника, и контролы BYOK (свой ключ).
 * Само значение ключа тут не хранится — ввод/вывод ключа идёт через [onSaveOwnKey]/[onClearOwnKey].
 */
private data class GeminiMtConfig(
    val modelId: String,
    val credentialMode: GeminiCredentialMode,
    val onCredentialModeChange: (GeminiCredentialMode) -> Unit,
    val endpoint: String,
    val onEndpointChange: (String) -> Unit,
    val ownKeyStored: Boolean,
    val onSaveOwnKey: (String) -> Unit,
    val onClearOwnKey: () -> Unit,
)

@Composable
private fun CloudProviderCard(
    providerId: String,
    state: CloudOptInState,
    onConsentChange: (Boolean) -> Unit,
    onDisclosureChange: (Boolean) -> Unit,
    geminiMtConfig: GeminiMtConfig? = null,
) {
    val s = LocalStrings.current
    // Берём локализованные тексты из активного каталога Strings; если для провайдера их нет —
    // показываем как заголовок его id (на случай новых провайдеров до того, как добавят перевод).
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
            // Переключатель = согласие пользователя. По умолчанию ВЫКЛ; включение само вызов не
            // запускает — canStart всё равно требует раскрытия + online + эфемерного токена.
            Switch(checked = state.userConsented, onCheckedChange = onConsentChange)
        }
        SecondaryText(role)

        if (geminiMtConfig != null) {
            GeminiMtFields(geminiMtConfig)
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
 * Все контролы конфига Gemini Flash MT: бейдж модели, переключатель режима credential и поля под
 * выбранный режим (endpoint бэкенда либо ввод BYOK-ключа).
 *
 * Безопасность: значение введённого ключа НЕ хранится в Compose-state нигде, кроме локального
 * буфера [keyDraft]. По нажатию Save оно уходит в [GeminiMtConfig.onSaveOwnKey] и тут же стирается
 * из буфера — сохранённое значение живёт только в EncryptedSharedPreferences. Поле использует
 * [PasswordVisualTransformation], так что ключ на экране замаскирован.
 */
@Composable
private fun GeminiMtFields(config: GeminiMtConfig) {
    val s = LocalStrings.current
    Badge(text = "model: ${config.modelId}", tone = BadgeTone.NEUTRAL, mono = true)

    // --- Переключатель режима credential: бэкенд / свой ключ ---
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = s.credentialModeLabel,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        GeminiCredentialMode.entries.forEach { mode ->
            val isSelected = mode == config.credentialMode
            Badge(
                text = if (mode == GeminiCredentialMode.BACKEND_MEDIATION) {
                    s.credentialModeBackend
                } else {
                    s.credentialModeOwnKey
                },
                tone = if (isSelected) BadgeTone.ACCENT else BadgeTone.NEUTRAL,
                modifier = Modifier.clickable { config.onCredentialModeChange(mode) },
            )
        }
    }

    when (config.credentialMode) {
        GeminiCredentialMode.BACKEND_MEDIATION -> {
            // Поле endpoint бэкенда (без API-ключа).
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

        GeminiCredentialMode.OWN_KEY -> {
            // Ввод BYOK-ключа: маскированный, действия save/clear, подсказка о сохранённом ключе.
            // keyDraft — эфемерный локальный state; чистим после Save, чтобы сырое значение не
            // болталось в композиции дольше момента ручного ввода.
            var keyDraft by remember { mutableStateOf("") }
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(s.ownKeyInputLabel) },
                placeholder = { Text(s.ownKeyInputPlaceholder) },
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (keyDraft.isNotBlank()) {
                            config.onSaveOwnKey(keyDraft)
                            keyDraft = "" // чистим сразу после передачи в защищённое хранилище
                        }
                    },
                    enabled = keyDraft.isNotBlank(),
                ) {
                    Text(s.ownKeySaveButton)
                }
                if (config.ownKeyStored) {
                    OutlinedButton(onClick = config.onClearOwnKey) {
                        Text(s.ownKeyClearButton)
                    }
                }
            }
            if (config.ownKeyStored) {
                Badge(text = s.ownKeyStoredHint, tone = BadgeTone.GREEN)
            }
            SecondaryText(s.ownKeyGeoRestrictionNote)
        }
    }
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
