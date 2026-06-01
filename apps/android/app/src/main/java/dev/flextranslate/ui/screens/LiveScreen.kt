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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.AudioCaptureController.CaptureStats
import dev.flextranslate.foundation.OfflineFirstState
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard
import dev.flextranslate.ui.theme.SurfaceColor

/**
 * Эфир / Live — the primary interpreter surface. Transcript-dominant, with mode/pair/readiness
 * always legible. Honors A1 no-false-claims: transcript + translation are placeholders only.
 *
 * @param onRequestPermission routes a blocked-capture state to the host RECORD_AUDIO request.
 */
@Composable
fun LiveScreen(
    session: LiveSessionState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        TranscriptPanel(session = session, modifier = Modifier.weight(1f))
        TranslationField(session)
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
                        "Распознаю тестовое аудио…"
                    } else {
                        "Demo: распознать тестовое ${session.sourceLanguage.code.uppercase()} аудио"
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(session: LiveSessionState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Mode: offline is the honest default; no cloud badge shown unless cloud is active (none in WS1).
        Badge(text = "offline", tone = BadgeTone.ACCENT)
        Badge(text = session.languagePairLabel, tone = BadgeTone.ACCENT, mono = true)
        ReadinessBadge(session.micPermission)
    }
}

@Composable
private fun ReadinessBadge(state: OfflineFirstState) {
    when (state) {
        OfflineFirstState.ReadyOfflineAsr ->
            Badge(text = "микрофон готов", tone = BadgeTone.GREEN)
        is OfflineFirstState.CaptureBlocked ->
            Badge(text = state.reason, tone = BadgeTone.RED)
        is OfflineFirstState.MissingOfflinePack ->
            Badge(text = "нет пакета: ${state.packId}", tone = BadgeTone.AMBER)
        is OfflineFirstState.UnsupportedOfflineTranslation ->
            Badge(text = "offline-перевод не заявлен", tone = BadgeTone.AMBER)
        // CloudDisabled is the default, not an error — render the neutral offline-default note.
        OfflineFirstState.CloudDisabled ->
            Badge(text = "облако выключено", tone = BadgeTone.NEUTRAL)
    }
}

@Composable
private fun MicLevelMeter(stats: CaptureStats?, isCapturing: Boolean, speechActive: Boolean) {
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
                Text("Уровень микрофона", style = MaterialTheme.typography.labelLarge)
                // Real energy-VAD indicator — honest RMS signal on real audio, NOT ASR.
                if (isCapturing) {
                    if (speechActive) {
                        Badge(text = "речь", tone = BadgeTone.ACCENT)
                    } else {
                        Badge(text = "тишина", tone = BadgeTone.NEUTRAL)
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
        // Horizontal bar — accent fill driven by real CaptureStats.levelPercent.
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
            SecondaryText("Простаивает — нажмите «Слушать», чтобы увидеть реальный уровень.")
        }
    }
}

@Composable
private fun TranscriptPanel(session: LiveSessionState, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier, radius = 16, container = SurfaceColor) {
        when {
            // No offline model for the selected source language — honest gated placeholder.
            !session.asrModelInstalled -> CenteredHint(
                "ASR support пока не заявлен — транскрипт появится после загрузки " +
                    "локальной модели для ${session.sourceLanguage.label} (см. Модели).",
            )
            // Model installed but nothing decoded yet — honest "listening" state, NOT fake text.
            session.finalTranscript.isBlank() && session.partialTranscript.isBlank() -> CenteredHint(
                if (session.isCapturing) {
                    "Слушаю… говорите на языке ${session.sourceLanguage.label}. " +
                        "Текст распознаётся локально (demo, качество не проверено)."
                } else {
                    "Нажмите «Слушать» — локальная модель ${session.sourceLanguage.label} готова."
                },
            )
            // Real recognizer output: finalized utterances + in-flight partial (muted).
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (session.finalTranscript.isNotBlank()) {
                    Text(
                        text = session.finalTranscript,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (session.partialTranscript.isNotBlank()) {
                    Text(
                        text = session.partialTranscript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

@Composable
private fun TranslationField(session: LiveSessionState) {
    // Real M2M-100 output only (G005/WS4). Never a fabricated translation: when no real result is
    // available we show the model's honest gating reason or a neutral hint.
    val translation = session.translation
    val reason = session.translationReason
    SectionCard(radius = 12, title = "Перевод") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(text = session.languagePairLabel, tone = BadgeTone.ACCENT, mono = true)
            Badge(
                text = session.selectedMtCandidate.displayName,
                tone = BadgeTone.NEUTRAL,
            )
            if (session.translating) Badge(text = "перевожу…", tone = BadgeTone.ACCENT)
        }
        when {
            translation != null && translation.isNotBlank() ->
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            reason != null -> Badge(text = reason, tone = BadgeTone.AMBER)
            session.translating -> SecondaryText("Перевод выполняется локально…")
            else -> SecondaryText(
                "Перевод появится после распознавания фразы " +
                    "(модель ${session.selectedMtCandidate.displayName}, demo, качество не проверено).",
            )
        }
    }
}

@Composable
private fun CaptureControl(
    permission: OfflineFirstState,
    isCapturing: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (permission) {
            OfflineFirstState.ReadyOfflineAsr -> {
                if (isCapturing) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Стоп") }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Слушать") }
                }
            }
            is OfflineFirstState.CaptureBlocked -> {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Разрешить микрофон") }
                SecondaryText(permission.reason)
            }
            is OfflineFirstState.MissingOfflinePack -> {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Слушать")
                }
                SecondaryText("Нет offline-пакета: ${permission.packId} (см. Модели).")
            }
            is OfflineFirstState.UnsupportedOfflineTranslation,
            OfflineFirstState.CloudDisabled,
            -> {
                // Mic is ready (permission granted); capture can start. The state here describes
                // translation/cloud disposition, not a capture blocker.
                if (isCapturing) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Стоп") }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Слушать") }
                }
            }
        }
    }
}
