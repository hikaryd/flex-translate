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
import dev.flextranslate.foundation.MtRoutingMode
import dev.flextranslate.ui.FlexLanguage
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard
import dev.flextranslate.ui.i18n.LocalStrings

/**
 * Языки — выбор источника/цели и честная поддержка по каждой паре. Поддержка offline-перевода
 * закрыта бенчмарком (никогда не заявляется голословно); offline-ASR адаптер — «demo».
 */
@Composable
fun LanguagesScreen(session: LiveSessionState, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(radius = 12, title = s.languagePairTitle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageSelector(
                    label = s.sourceLabel,
                    selected = session.sourceLanguage,
                    onSelect = session::selectSource,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = session::swapLanguages) {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = s.swapLanguagesDescription,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                LanguageSelector(
                    label = s.targetLabel,
                    selected = session.targetLanguage,
                    onSelect = session::selectTarget,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        MtModelPicker(session)

        SectionCard(radius = 12, title = s.pairSupportTitle(session.languagePairLabel)) {
            // Пока нет бенчмарков, offline-перевод не заявляем (amber). ASR-адаптер — demo.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(text = s.offlineAsrAdapterReady, tone = BadgeTone.AMBER)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(text = s.offlineTranslationNotClaimedLong, tone = BadgeTone.AMBER)
            }
        }

        SecondaryText(s.supportFromBenchmarksFooter)
    }
}

/**
 * Модель перевода — выбор MT-модели (требование «несколько моделей по качеству/скорости, выбирает
 * пользователь»). Сверху селектор режима маршрутизации (AUTO / ON_DEVICE / CLOUD), ниже список всех
 * [MtCandidate], чтобы можно было закрепить конкретную модель на устройстве. Облачные кандидаты
 * выбираемы, но закрыты гейтом, пока не настроены согласие + учётные данные. Честно: ни одна модель
 * не помечена как «поддерживается».
 */
@Composable
private fun MtModelPicker(session: LiveSessionState) {
    val s = LocalStrings.current
    SectionCard(radius = 12, title = s.mtModelPickerTitle) {
        // ---- Селектор режима маршрутизации ----------------------------------------------------------
        SecondaryText(s.mtRoutingModeTitle)
        SecondaryText(s.mtRoutingModeAutoHint)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MtRoutingMode.entries.forEach { mode ->
                val isSelected = mode == session.selectedRoutingMode
                val label = when (mode) {
                    MtRoutingMode.AUTO -> s.mtRoutingModeAuto
                    MtRoutingMode.ON_DEVICE -> s.mtRoutingModeOnDevice
                    MtRoutingMode.CLOUD -> s.mtRoutingModeCloud
                }
                Badge(
                    text = label,
                    tone = if (isSelected) BadgeTone.ACCENT else BadgeTone.NEUTRAL,
                    modifier = Modifier.clickable { session.selectRoutingMode(mode) },
                )
            }
        }

        // ---- Список кандидатов на устройстве (закреплённая модель для AUTO/ON_DEVICE) -------------
        SecondaryText(s.mtModelPickerHint)
        session.mtCandidates.forEach { candidate ->
            MtCandidateRow(
                candidate = candidate,
                selected = candidate.id == session.selectedMtCandidate.id,
                // Состояние установки по строке: каждый кандидат на устройстве смотрит на СВОИ файлы,
                // а не только выбранный (как в ModelsScreen). Облачные / без spec кандидаты дают false.
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
    val s = LocalStrings.current
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
            if (selected) Badge(text = s.selected, tone = BadgeTone.GREEN)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(
                text = if (candidate.execution == MtExecution.CLOUD) s.executionCloud else s.executionOnDevice,
                tone = if (candidate.execution == MtExecution.CLOUD) BadgeTone.AMBER else BadgeTone.ACCENT,
            )
            Badge(text = s.qualityBadge(candidate.quality.label), tone = BadgeTone.NEUTRAL)
            Badge(text = s.speedBadge(candidate.speed.label), tone = BadgeTone.NEUTRAL)
            Badge(text = candidate.approxSizeLabel, tone = BadgeTone.NEUTRAL, mono = true)
        }
        SecondaryText(candidate.notes)
        when {
            candidate.execution == MtExecution.CLOUD ->
                Badge(text = s.cloudCallInWs5, tone = BadgeTone.AMBER)
            installed ->
                Badge(text = s.mtModelInstalledLocal, tone = BadgeTone.GREEN)
            candidate.modelId != null ->
                Badge(text = s.mtModelNotInstalled, tone = BadgeTone.AMBER)
            else ->
                Badge(text = s.mtModelOptional, tone = BadgeTone.NEUTRAL)
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
