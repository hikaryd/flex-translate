package dev.flextranslate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.AudioCaptureController.CaptureStats
import dev.flextranslate.foundation.OfflineFirstState
import dev.flextranslate.ui.DialogueTurn
import dev.flextranslate.ui.FlexLanguage
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard
import dev.flextranslate.ui.i18n.LocalStrings
import dev.flextranslate.ui.theme.SurfaceColor
import dev.flextranslate.ui.theme.SurfaceElevated
import dev.flextranslate.ui.theme.SurfaceHighest

/**
 * Эфир / Live — экран двустороннего синхронного переводчика. Лог разговора показываем как чат:
 * каждый [DialogueTurn] — это оригинал (на языке говорящего) и перевод (на язык собеседника),
 * раскиданные по сторонам (источник слева, цель справа). Незавершённый кусок распознавания висит
 * внизу области лога.
 *
 * Дисциплина A1/A2: весь текст в репликах — это НАСТОЯЩИЙ вывод ASR/MT, ничего не выдумываем. Если
 * перевод заблокирован (модель не установлена / облако недоступно) — показываем честную причину,
 * а не фейковый текст.
 *
 * @param onRequestPermission прокидывает запрос RECORD_AUDIO наверх, когда захват заблокирован.
 */
@Composable
fun LiveScreen(
    session: LiveSessionState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val permission = session.micPermission
    val stats = session.stats
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusStrip(session)
        MicLevelMeter(
            stats = stats,
            isCapturing = session.isCapturing,
            speechActive = session.speechActive,
        )
        // Кто говорит: смена направления + бейдж текущего языка + кнопка очистки.
        WhoSpeaksRow(session = session)
        // Лог разговора занимает всё оставшееся место.
        ConversationLog(
            session = session,
            modifier = Modifier.weight(1f),
        )
        CaptureControl(
            permission = permission,
            isCapturing = session.isCapturing,
            onStart = session::startCapture,
            onStop = session::stopCapture,
            onRequestPermission = onRequestPermission,
        )
        if (session.demoClipAvailable && !session.isCapturing) {
            OutlinedButton(
                onClick = session::runWavDemo,
                enabled = !session.demoRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (session.demoRunning) {
                        s.demoRecognizing
                    } else {
                        s.demoRecognizeButton(session.sourceLanguage.code.uppercase())
                    },
                )
            }
        }
    }
}

// ---- Кто говорит --------------------------------------------------------------------------------

/**
 * Строка с текущим языком говорящего, кнопкой смены направления и кнопкой очистки. Тап по смене
 * дёргает [LiveSessionState.swapLanguages], после чего следующую реплику распознаём уже на новом
 * исходном языке и переводим на новый целевой.
 */
@Composable
private fun WhoSpeaksRow(session: LiveSessionState) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Бейдж исходного языка (кто сейчас говорит).
        Badge(
            text = s.dialogueSpeakingLabel(session.sourceLanguage.label),
            tone = BadgeTone.ACCENT,
        )
        // Стрелка направления перевода.
        Badge(
            text = "→ ${session.targetLanguage.label}",
            tone = BadgeTone.NEUTRAL,
        )
        // Поменять говорящего местами.
        IconButton(onClick = session::swapLanguages) {
            Icon(
                Icons.Default.SwapVert,
                contentDescription = s.swapLanguagesDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // Распорка прижимает кнопку очистки к правому краю.
        Box(modifier = Modifier.weight(1f))
        if (session.conversationLog.isNotEmpty()) {
            TextButton(onClick = session::clearDialogue) {
                Text(
                    text = s.dialogueClearButton,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- Лог разговора ------------------------------------------------------------------------------

/**
 * Прокручиваемый лог разговора в стиле чата. Каждый [DialogueTurn] — пузырь, привязанный к стороне
 * говорящего: реплики на исходном языке слева, на целевом — справа. Незавершённое распознавание
 * висит приглушённой строкой внизу.
 */
@Composable
private fun ConversationLog(session: LiveSessionState, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val log = session.conversationLog
    val listState = rememberLazyListState()

    // Автоскролл вниз при каждом росте лога или изменении незавершённого куска.
    val itemCount = log.size + (if (session.partialTranscript.isNotBlank()) 1 else 0)
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SurfaceColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
    ) {
        when {
            !session.asrModelInstalled -> CenteredHint(
                s.asrNotClaimedHint(session.sourceLanguage.label),
            )

            log.isEmpty() && session.partialTranscript.isBlank() -> CenteredHint(
                if (session.isCapturing) {
                    s.dialogueEmptyHint
                } else {
                    s.readyToListenHint(session.sourceLanguage.label)
                },
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = log, key = { it.id }) { turn ->
                    DialogueTurnBubble(turn = turn)
                }
                // Незавершённый кусок внизу — приглушённый, это ещё не финальная реплика.
                if (session.partialTranscript.isNotBlank()) {
                    item(key = "partial") {
                        PartialTranscriptRow(
                            text = session.partialTranscript,
                            language = session.sourceLanguage,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Пузырь для одной реплики [DialogueTurn]. Реплики на исходном языке — слева, на целевом
 * (т.е. где язык произнесённого совпадает с текущим целевым) — справа. Разделение по сторонам
 * помогает следить за диалогом двух людей.
 *
 * В каждом пузыре:
 *  - бейдж языка (например, "Русский") из [DialogueTurn.spokenLanguage];
 *  - оригинальный текст ASR (настоящий, не выдуманный);
 *  - перевод MT, либо индикатор ожидания, либо честная причина блокировки.
 */
@Composable
private fun DialogueTurnBubble(turn: DialogueTurn) {
    val s = LocalStrings.current
    // Нужен стабильный признак, к какой стороне привязать пузырь. Берём язык произнесённого: в
    // диалоге стороны чередуют языки. Сравниваем ordinal: у кого меньше — тот слева. Так смена
    // sourceLanguage ↔ targetLanguage не ломает раскладку — реплика всегда садится на свою сторону.
    val alignRight = turn.spokenLanguage.ordinal > turn.translationLanguage.ordinal

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            color = if (alignRight) SurfaceHighest else SurfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (alignRight) 12.dp else 4.dp,
                bottomEnd = if (alignRight) 4.dp else 12.dp,
            ),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Бейдж языка.
                Badge(
                    text = turn.spokenLanguage.label,
                    tone = if (alignRight) BadgeTone.NEUTRAL else BadgeTone.ACCENT,
                )
                // Оригинал ASR — настоящий вывод распознавателя.
                Text(
                    text = turn.originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Слот под перевод.
                when {
                    turn.translatedText != null -> Text(
                        text = turn.translatedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    turn.translationReason != null -> Badge(
                        text = turn.translationReason,
                        tone = BadgeTone.AMBER,
                    )
                    else -> Text(
                        text = s.dialoguePendingTranslation,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PartialTranscriptRow(text: String, language: FlexLanguage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            color = SurfaceColor,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Badge(text = language.label, tone = BadgeTone.NEUTRAL)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// ---- Полоса статуса -----------------------------------------------------------------------------

@Composable
private fun StatusStrip(session: LiveSessionState) {
    val s = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge(text = s.modeOffline, tone = BadgeTone.ACCENT)
        Badge(text = session.languagePairLabel, tone = BadgeTone.ACCENT, mono = true)
        ReadinessBadge(session.micPermission)
    }
}

@Composable
private fun ReadinessBadge(state: OfflineFirstState) {
    val s = LocalStrings.current
    when (state) {
        OfflineFirstState.ReadyOfflineAsr ->
            Badge(text = s.micReady, tone = BadgeTone.GREEN)
        is OfflineFirstState.CaptureBlocked ->
            Badge(text = state.reason, tone = BadgeTone.RED)
        is OfflineFirstState.MissingOfflinePack ->
            Badge(text = s.missingPackBadge(state.packId), tone = BadgeTone.AMBER)
        is OfflineFirstState.UnsupportedOfflineTranslation ->
            Badge(text = s.offlineTranslationNotClaimed, tone = BadgeTone.AMBER)
        OfflineFirstState.CloudDisabled ->
            Badge(text = s.cloudDisabledBadge, tone = BadgeTone.NEUTRAL)
    }
}

// ---- Индикатор уровня микрофона -----------------------------------------------------------------

@Composable
private fun MicLevelMeter(stats: CaptureStats?, isCapturing: Boolean, speechActive: Boolean) {
    val s = LocalStrings.current
    SectionCard(radius = 12) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.micLevelTitle, style = MaterialTheme.typography.labelLarge)
                if (isCapturing) {
                    if (speechActive) {
                        Badge(text = s.speech, tone = BadgeTone.ACCENT)
                    } else {
                        Badge(text = s.silence, tone = BadgeTone.NEUTRAL)
                    }
                }
            }
            val levelLabel = if (isCapturing && stats != null) "${stats.levelPercent}%" else "—"
            Text(
                text = levelLabel,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(SurfaceColor),
        ) {
            val fraction = if (isCapturing && stats != null) (stats.levelPercent / 100f).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        if (isCapturing && stats != null) {
            Text(
                text = "peak ${stats.peak}  ·  rms ${"%.0f".format(stats.rms)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SecondaryText(s.micIdleHint)
        }
    }
}

// ---- Управление захватом ------------------------------------------------------------------------

@Composable
private fun CaptureControl(
    permission: OfflineFirstState,
    isCapturing: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (permission) {
            OfflineFirstState.ReadyOfflineAsr -> {
                if (isCapturing) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(s.stop) }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(s.listen) }
                }
            }
            is OfflineFirstState.CaptureBlocked -> {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text(s.grantMic) }
                SecondaryText(permission.reason)
            }
            is OfflineFirstState.MissingOfflinePack -> {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(s.listen)
                }
                SecondaryText(s.missingPackHint(permission.packId))
            }
            is OfflineFirstState.UnsupportedOfflineTranslation,
            OfflineFirstState.CloudDisabled,
            -> {
                if (isCapturing) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(s.stop) }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(s.listen) }
                }
            }
        }
    }
}
