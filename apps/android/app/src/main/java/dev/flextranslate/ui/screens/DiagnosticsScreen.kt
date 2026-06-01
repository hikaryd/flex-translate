package dev.flextranslate.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.AudioCaptureController.CaptureStats
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard
import dev.flextranslate.ui.components.StatRow
import dev.flextranslate.ui.i18n.LocalStrings
import dev.flextranslate.ui.theme.SemanticRed

private const val PENDING = "pending"
private const val UNAVAILABLE = "—"

/**
 * Диагностика / Diagnostics — operator trust + debugging. Capture values are real (from
 * [CaptureStats]); pipeline/telemetry values are honestly "pending" / "—" until WS2/WS6 land.
 * No fabricated metrics. Section titles are localised; low-level stat keys (camelCase identifiers
 * like `sampleRateHz`, `vadState`) are left as technical tokens — language-neutral by design.
 */
@Composable
fun DiagnosticsScreen(session: LiveSessionState, modifier: Modifier = Modifier) {
    val stats = session.stats
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CaptureSection(stats)
        PipelineSection(
            asrProviderId = session.asrProviderId,
            vadStateLabel = if (session.isCapturing) session.vadState.name else UNAVAILABLE,
        )
        BuildDeviceSection()
        TelemetrySection()
    }
}

@Composable
private fun CaptureSection(stats: CaptureStats?) {
    val s = LocalStrings.current
    SectionCard(radius = 12, title = s.captureSectionTitle) {
        StatRow("isCapturing", (stats?.isCapturing ?: false).toString())
        StatRow("sampleRateHz", stats?.sampleRateHz?.toString() ?: UNAVAILABLE)
        StatRow("framesRead", stats?.framesRead?.toString() ?: UNAVAILABLE)
        StatRow("chunksRead", stats?.chunksRead?.toString() ?: UNAVAILABLE)
        StatRow("elapsedMs", stats?.elapsedMs?.toString() ?: UNAVAILABLE)
        StatRow("peak", stats?.peak?.toString() ?: UNAVAILABLE)
        StatRow("rms", stats?.let { "%.1f".format(it.rms) } ?: UNAVAILABLE)
        StatRow(
            label = "lastError",
            value = stats?.lastError ?: UNAVAILABLE,
            valueTone = if (stats?.lastError != null) SemanticRed else null,
        )
    }
}

@Composable
private fun PipelineSection(asrProviderId: String, vadStateLabel: String) {
    val s = LocalStrings.current
    SectionCard(radius = 12, title = s.pipelineSectionTitle) {
        // Real energy-VAD state while capturing; "—" when idle. This is honest RMS-VAD, not ASR.
        StatRow("vadState", vadStateLabel)
        StatRow("asrProvider", asrProviderId)
        StatRow("asrSupport", s.asrSupportNotClaimed)
        StatRow("bufferDepth", PENDING)
        StatRow("latencyP95Ms", PENDING)
    }
}

@Composable
private fun BuildDeviceSection() {
    val s = LocalStrings.current
    SectionCard(radius = 12, title = s.buildDeviceSectionTitle) {
        StatRow("appBuild", "0.1.0")
        StatRow("deviceModel", Build.MODEL)
        StatRow("deviceTier", deviceTier())
        StatRow("osVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        StatRow("runtimeVersion", PENDING)
    }
}

@Composable
private fun TelemetrySection() {
    val s = LocalStrings.current
    SectionCard(radius = 12, title = s.telemetrySectionTitle) {
        SecondaryText(s.telemetryPendingHint)
        StatRow("lastEvents", PENDING)
    }
}

/** SM-S937B (Galaxy S25-class) is a known high-tier device; otherwise honestly "—". */
private fun deviceTier(): String = if (Build.MODEL.contains("S937", ignoreCase = true)) "high" else UNAVAILABLE
