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
 * Эфир / Live — the two-way dialogue interpreter surface. The conversation log is rendered as a
 * chat-style list; each [DialogueTurn] shows the original (in the speaker's language) and its
 * translation (into the counterpart language), attributed by side (source=left, target=right).
 * The in-flight partial transcript appears at the bottom of the log area.
 *
 * A1/A2 discipline: all text in turns is GENUINE ASR/MT output — never fabricated. A turn whose
 * translation is gated (model not installed / cloud blocked) shows the honest reason, not fake text.
 *
 * @param onRequestPermission routes a blocked-capture state to the host RECORD_AUDIO request.
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
        // Who-speaks control row: swap + current speaking language badge + clear button.
        WhoSpeaksRow(session = session)
        // Conversation log fills remaining space.
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

// ---- Who-speaks control -------------------------------------------------------------------------

/**
 * Row that shows the current speaking language, a swap button (to flip who speaks next), and the
 * clear button. Tapping swap calls [LiveSessionState.swapLanguages] so the next utterance will be
 * recognized in the new source language and translated into the new target language.
 */
@Composable
private fun WhoSpeaksRow(session: LiveSessionState) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Source language badge (current speaker).
        Badge(
            text = s.dialogueSpeakingLabel(session.sourceLanguage.label),
            tone = BadgeTone.ACCENT,
        )
        // Arrow indicating direction.
        Badge(
            text = "→ ${session.targetLanguage.label}",
            tone = BadgeTone.NEUTRAL,
        )
        // Swap who speaks.
        IconButton(onClick = session::swapLanguages) {
            Icon(
                Icons.Default.SwapVert,
                contentDescription = s.swapLanguagesDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // Spacer pushes clear button to the end.
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

// ---- Conversation log ---------------------------------------------------------------------------

/**
 * Scrollable chat-style conversation log. Each [DialogueTurn] is rendered as a bubble attributed
 * to the speaking side: source-language utterances are left-aligned, target-language utterances are
 * right-aligned. The in-flight partial transcript appears as a muted row at the bottom.
 */
@Composable
private fun ConversationLog(session: LiveSessionState, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val log = session.conversationLog
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom whenever the log grows or the partial changes.
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
                // In-flight partial at the bottom — muted, not a finalized turn.
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
 * Chat bubble for a single [DialogueTurn]. Source-language turns are left-aligned; target-language
 * turns (i.e. turns where the spoken language equals what is currently the target) are
 * right-aligned. The visual distinction makes it easy to follow a two-person conversation.
 *
 * Each bubble shows:
 *  - A language badge (e.g. "Русский") in the turn's [DialogueTurn.spokenLanguage].
 *  - The original ASR text (genuine — never fabricated).
 *  - The MT translation, or a pending indicator, or an honest gating reason.
 */
@Composable
private fun DialogueTurnBubble(turn: DialogueTurn) {
    val s = LocalStrings.current
    // We use a simple heuristic: even-indexed turns alternate. However the correct signal is the
    // spoken language itself — the two parties alternate languages in dialogue mode. We choose
    // alignment based on the spoken language: the first language encountered anchors left, and the
    // counterpart language anchors right. In practice sourceLanguage ↔ targetLanguage swap drives
    // this naturally, but we need a stable signal. We use the turn's spokenLanguage code ordering.
    // For simplicity: RU/ZH-speaking turns go left; EN-speaking turns go right. For arbitrary
    // pairs we compare the spoken language's ordinal: lower ordinal = left.
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
                // Language badge.
                Badge(
                    text = turn.spokenLanguage.label,
                    tone = if (alignRight) BadgeTone.NEUTRAL else BadgeTone.ACCENT,
                )
                // Original ASR text — genuine recognizer output.
                Text(
                    text = turn.originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Translation slot.
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

// ---- Status strip -------------------------------------------------------------------------------

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

// ---- Mic level meter ----------------------------------------------------------------------------

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

// ---- Capture control ----------------------------------------------------------------------------

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
