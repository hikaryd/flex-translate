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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.MtCandidate
import dev.flextranslate.foundation.MtExecution
import dev.flextranslate.ui.FlexLanguage
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard

/**
 * Языки / Languages — pick source/target and show honest per-pair support. Offline-translation
 * support is benchmark-gated (never claimed); offline-ASR adapter is "demo".
 */
@Composable
fun LanguagesScreen(session: LiveSessionState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(radius = 12, title = "Языковая пара") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageSelector(
                    label = "Источник",
                    selected = session.sourceLanguage,
                    onSelect = session::selectSource,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = session::swapLanguages) {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = "Поменять языки местами",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                LanguageSelector(
                    label = "Цель",
                    selected = session.targetLanguage,
                    onSelect = session::selectTarget,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        MtModelPicker(session)

        SectionCard(radius = 12, title = "Поддержка пары ${session.languagePairLabel}") {
            // Until benchmarks exist, offline translation is "не заявлен" (amber). ASR adapter is demo.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(text = "offline-ASR: адаптер готов (demo)", tone = BadgeTone.AMBER)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(
                    text = "offline-перевод: не заявлен (нужны benchmark + модель)",
                    tone = BadgeTone.AMBER,
                )
            }
        }

        SecondaryText(
            "Поддержка генерируется из benchmark-доказательств, а не из намерений.",
        )
    }
}

/**
 * Модель перевода — the MT model picker (the "several models by quality/speed, user chooses"
 * requirement). Lists every [MtCandidate] with its quality/speed/size; the user's choice drives the
 * runtime. Cloud candidates are selectable but gated until WS5; on-device candidates show an
 * install hint when their files are absent. Honest: no model is marked "supported".
 */
@Composable
private fun MtModelPicker(session: LiveSessionState) {
    SectionCard(radius = 12, title = "Модель перевода") {
        SecondaryText("Выберите модель по качеству/скорости. Выбор используется в диалоге.")
        session.mtCandidates.forEach { candidate ->
            MtCandidateRow(
                candidate = candidate,
                selected = candidate.id == session.selectedMtCandidate.id,
                // Per-row install state: every on-device candidate reflects its OWN files, not just
                // the selected one (mirrors ModelsScreen). Cloud / spec-less candidates resolve false.
                installed = session.isMtModelInstalled(candidate),
                onSelect = { session.selectMtCandidate(candidate) },
            )
        }
    }
}

@Composable
private fun MtCandidateRow(
    candidate: MtCandidate,
    selected: Boolean,
    installed: Boolean,
    onSelect: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else null
    SectionCard(
        radius = 10,
        modifier = Modifier.clickable(onClick = onSelect),
        container = container ?: MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = candidate.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (selected) Badge(text = "выбрано", tone = BadgeTone.GREEN)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(
                text = if (candidate.execution == MtExecution.CLOUD) "облако" else "на устройстве",
                tone = if (candidate.execution == MtExecution.CLOUD) BadgeTone.AMBER else BadgeTone.ACCENT,
            )
            Badge(text = "качество: ${candidate.quality.label}", tone = BadgeTone.NEUTRAL)
            Badge(text = "скорость: ${candidate.speed.label}", tone = BadgeTone.NEUTRAL)
            Badge(text = candidate.approxSizeLabel, tone = BadgeTone.NEUTRAL, mono = true)
        }
        SecondaryText(candidate.notes)
        when {
            candidate.execution == MtExecution.CLOUD ->
                Badge(text = "реальный вызов в WS5 (нужны согласие + сеть)", tone = BadgeTone.AMBER)
            installed ->
                Badge(text = "модель установлена — перевод локальный", tone = BadgeTone.GREEN)
            candidate.modelId != null ->
                Badge(text = "модель не установлена (см. Модели)", tone = BadgeTone.AMBER)
            else ->
                Badge(text = "опционально — пакет ещё не добавлен", tone = BadgeTone.NEUTRAL)
        }
    }
}

@Composable
private fun LanguageSelector(
    label: String,
    selected: FlexLanguage,
    onSelect: (FlexLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SecondaryText(label)
        SectionCard(radius = 10, modifier = Modifier.clickable { expanded = true }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected.label, style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                FlexLanguage.entries.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.label) },
                        onClick = {
                            onSelect(language)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
