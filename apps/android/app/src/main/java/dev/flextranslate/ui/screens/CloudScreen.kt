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

private data class CloudProviderCopy(val title: String, val role: String, val disclosure: String)

private val providerCopy = mapOf(
    GeminiFlashTranslationProvider.PROVIDER_ID to CloudProviderCopy(
        title = "Gemini Flash · облачный перевод (MT)",
        role = "Наивысшее качество перевода через облако. Только текст финализированной фразы — " +
            "аудио в этом режиме не отправляется.",
        disclosure = "Что уходит с устройства: только текст распознанной (финализированной) фразы " +
            "текущего высказывания — аудио для текстового MT-режима НЕ отправляется.\n" +
            "Куда: на наш backend, который пересылает запрос в Google Gemini API. " +
            "Ключ Gemini хранится только на сервере — в приложении нет встроенных API-ключей.\n" +
            "Хранение: согласно политике обработки данных провайдера. Приложение не хранит " +
            "транскрипты дольше сессии, если вы сами не включите историю.\n" +
            "Облако выключено по умолчанию и не подменяет офлайн-перевод незаметно: если облако " +
            "недоступно — показывается честная причина, а офлайн-модель продолжает работать.",
    ),
    "cloud-stt-recognition-fallback" to CloudProviderCopy(
        title = "Cloud STT · fallback распознавания",
        role = "Облачное распознавание как запасной вариант, когда offline-модель не справляется.",
        disclosure = "Аудио уходит на сервер только при явном включении. Согласие на обработку и " +
            "политику хранения данных нужно подтвердить отдельно.",
    ),
    "gemini-live-assistant" to CloudProviderCopy(
        title = "Gemini Live · realtime ассистент",
        role = "Realtime-ассистент поверх живого аудио (низкая задержка).",
        disclosure = "Потоковая передача аудио в реальном времени. Требует согласия, принятого " +
            "disclosure и эфемерного токена от backend.",
    ),
    "gemini-batch-audio-enrichment" to CloudProviderCopy(
        title = "Gemini batch · async обогащение",
        role = "Асинхронное пакетное обогащение записанных фрагментов.",
        disclosure = "Фрагменты отправляются пакетами после сессии. Удержание данных описывается " +
            "в политике провайдера; согласие обязательно.",
    ),
)

/**
 * Облако / Cloud (Settings) — opt-in cloud, default OFF, honest disclosure. No silent fallback,
 * no embedded API keys; cloud calls require backend ephemeral tokens.
 */
@Composable
fun CloudScreen(session: LiveSessionState, modifier: Modifier = Modifier) {
    val states by session.cloudStates
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(radius = 12, title = "Облако") {
            SecondaryText(
                "Облако выключено по умолчанию · нет silent fallback · нет встроенных " +
                    "API-ключей (backend ephemeral tokens).",
            )
        }
        states.forEach { state ->
            val copy = providerCopy[state.providerId]
                ?: CloudProviderCopy(state.providerId, "", "")
            val isCloudMt = state.providerId == GeminiFlashTranslationProvider.PROVIDER_ID
            CloudProviderCard(
                copy = copy,
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

/** Backend-mediation fields surfaced only on the Gemini Flash MT card. No API key — endpoint only. */
private data class BackendConfig(
    val modelId: String,
    val endpoint: String,
    val onEndpointChange: (String) -> Unit,
)

@Composable
private fun CloudProviderCard(
    copy: CloudProviderCopy,
    state: CloudOptInState,
    onConsentChange: (Boolean) -> Unit,
    onDisclosureChange: (Boolean) -> Unit,
    backendConfig: BackendConfig? = null,
) {
    var disclosureExpanded by remember { mutableStateOf(false) }
    val nowEpochMs = remember { System.currentTimeMillis() }
    SectionCard(radius = 12) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = copy.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // Toggle is the user-consent intent. Default OFF; enabling never auto-starts a call —
            // canStart still requires disclosure + online + ephemeral token.
            Switch(checked = state.userConsented, onCheckedChange = onConsentChange)
        }
        SecondaryText(copy.role)

        if (backendConfig != null) {
            BackendMediationFields(backendConfig)
        }

        Text(
            text = if (disclosureExpanded) "Скрыть раскрытие данных" else "Показать раскрытие данных",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { disclosureExpanded = !disclosureExpanded },
        )
        if (disclosureExpanded) {
            SecondaryText(copy.disclosure)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Принять раскрытие", style = MaterialTheme.typography.bodySmall)
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
        label = { Text("Backend endpoint (без ключа Gemini)") },
        placeholder = { Text("https://flex-backend.example.com") },
    )
    SecondaryText(
        "Перевод идёт через ваш backend (mediation): он хранит ключ Gemini на сервере и возвращает " +
            "только текст. Пока endpoint не указан — облачный перевод честно заблокирован.",
    )
}

@Composable
private fun StateLine(state: CloudOptInState, nowEpochMs: Long) {
    if (state.canStart(nowEpochMs)) {
        Badge(text = "готово к запуску", tone = BadgeTone.GREEN)
        return
    }
    val missing = buildList {
        if (!state.userConsented) add("согласие")
        if (!state.disclosureAccepted) add("раскрытие")
        if (state.networkState != "online") add("онлайн")
        if (state.credential?.isEphemeral(nowEpochMs) != true) add("эфемерный токен")
    }
    Badge(text = "выключено · не хватает: ${missing.joinToString(", ")}", tone = BadgeTone.NEUTRAL)
}
