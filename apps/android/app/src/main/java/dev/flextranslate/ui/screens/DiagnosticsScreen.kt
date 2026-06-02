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
import dev.flextranslate.foundation.TelemetrySink
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard
import dev.flextranslate.ui.components.StatRow
import dev.flextranslate.ui.i18n.LocalStrings
import dev.flextranslate.ui.theme.SemanticRed

private const val UNAVAILABLE = "—"

private const val TELEMETRY_RECENT_COUNT = 5

/**
 * Диагностика — доверие оператора + отладка. Значения захвата настоящие (из [CaptureStats]),
 * конвейер показывает реальное состояние VAD/ASR. Раздел телеметрии показывает НАСТОЯЩИЕ свежие
 * события из [TelemetrySink] и посчитанные p50/p95 по реальным сэмплам (честное «—», пока сэмплов
 * нет). Нигде ничего не выдумываем.
 *
 * Заголовки разделов локализованы; низкоуровневые ключи статов (camelCase вроде `sampleRateHz`,
 * `vadState`) оставлены техническими токенами — намеренно не зависят от языка.
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
        TelemetrySection(session.telemetrySink)
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
        // Реальное состояние energy-VAD во время захвата; «—» в простое. Это честный RMS-VAD, а не ASR.
        StatRow("vadState", vadStateLabel)
        StatRow("asrProvider", asrProviderId)
        StatRow("asrSupport", s.asrSupportNotClaimed)
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
    }
}

@Composable
private fun TelemetrySection(sink: TelemetrySink) {
    val s = LocalStrings.current
    // Читаем из sink — это снимки на момент композиции; экран рекомпозится при каждом открытии,
    // так что значения свежие. Ничего не выдумываем: либо посчитано по реальным сэмплам, либо «—».
    val recentEvents = sink.recent(TELEMETRY_RECENT_COUNT)
    val mtPercentiles = sink.latencyPercentiles(TelemetrySink.EVT_MT_END, "latency_ms")
    val asrCount = sink.recent(sink.size)
        .count { it.eventType == TelemetrySink.EVT_ASR_FINAL }

    SectionCard(radius = 12, title = s.telemetrySectionTitle) {
        if (recentEvents.isEmpty()) {
            SecondaryText(s.telemetryNoEventsYet)
        } else {
            // Показываем последние N событий компактными строками тип+ts (без транскриптов и PII).
            recentEvents.reversed().forEachIndexed { index, event ->
                StatRow(
                    label = "event[$index]",
                    value = "${event.eventType} @${event.monotonicTsMs}ms",
                )
            }
        }
        StatRow("totalAccepted", sink.totalAccepted.toString())
        StatRow("totalDropped", sink.totalDropped.toString())
        StatRow("asrFinalCount", asrCount.toString())
        // p50/p95 задержки MT — честное «—», пока ни одного MT-события не записано.
        StatRow(
            label = "mtLatencyP50ms",
            value = mtPercentiles.p50Ms?.toString() ?: UNAVAILABLE,
        )
        StatRow(
            label = "mtLatencyP95ms",
            value = mtPercentiles.p95Ms?.toString() ?: UNAVAILABLE,
        )
        StatRow(
            label = "mtLatencySamples",
            value = if (mtPercentiles.sampleCount > 0) mtPercentiles.sampleCount.toString()
                    else UNAVAILABLE,
        )
    }
}

/** SM-S937B (класса Galaxy S25) — заведомо high-tier устройство; иначе честно «—». */
private fun deviceTier(): String = if (Build.MODEL.contains("S937", ignoreCase = true)) "high" else UNAVAILABLE
