package dev.flextranslate.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.AsrCandidateRegistry
import dev.flextranslate.foundation.MtCandidateRegistry
import dev.flextranslate.foundation.MtExecution
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard

/** A single offline pack row, derived honestly from the candidate registries + on-device store. */
private data class OfflinePack(
    val id: String,
    val kind: String,
    val tierLabel: String,
    val installed: Boolean,
    val totalSizeMb: Double?,
    /**
     * License pass-through for restricted on-device models (Gemma-derived MiLMMT): the notice text
     * the app MUST surface and the terms URL the user accepts. Null for permissive packs.
     */
    val licenseNotice: String? = null,
    val licenseTermsUrl: String? = null,
)

/**
 * Модели / Models & offline packs — manage offline ASR/MT packs honestly. Weights are NOT
 * bundled. ASR packs known to the sherpa-onnx runtime show their REAL on-device install state and
 * size (from [AsrModelStore]); everything else shows "не установлен" with size "—". Nothing is
 * marked "supported" — that still requires WS6 benchmark evidence.
 */
@Composable
fun ModelsScreen(session: LiveSessionState, isOnline: Boolean = false, modifier: Modifier = Modifier) {
    val packs = remember(session) { buildOfflinePacks(session) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(radius = 12, title = "Offline-пакеты") {
                SecondaryText(
                    "Веса моделей не входят в сборку (лицензия/размер). Установленные пакеты " +
                        "показывают реальный размер на устройстве. Поддержка не заявляется без " +
                        "benchmark-доказательств.",
                )
            }
        }
        items(packs, key = { it.id }) { pack ->
            PackRow(pack = pack, isOnline = isOnline)
        }
    }
}

@Composable
private fun PackRow(pack: OfflinePack, isOnline: Boolean) {
    val installed = pack.installed
    SectionCard(radius = 12) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pack.id,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Badge(text = pack.kind, tone = BadgeTone.ACCENT)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(text = pack.tierLabel, tone = BadgeTone.NEUTRAL, mono = true)
            Text(
                text = sizeLabel(pack),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (installed) {
                Badge(text = "установлен", tone = BadgeTone.GREEN)
            } else {
                Badge(text = "не установлен", tone = BadgeTone.NEUTRAL)
            }
            OutlinedButton(onClick = {}, enabled = isOnline && !installed) { Text("Скачать") }
        }
        if (installed) {
            SecondaryText("Готов к локальному распознаванию (demo, качество не проверено).")
        } else if (!isOnline) {
            SecondaryText("Доступно только онлайн. Контрольная сумма проверяется после загрузки.")
        }
        // Gemma pass-through: the license REQUIRES surfacing the terms + prohibited-use policy to
        // the user for the MiLMMT (Gemma-derived) pack. Shown for both states (informs before
        // download and reminds when installed). Tapping the link opens the Gemma terms.
        pack.licenseNotice?.let { notice ->
            LicenseDisclosure(notice = notice, termsUrl = pack.licenseTermsUrl)
        }
    }
}

@Composable
private fun LicenseDisclosure(notice: String, termsUrl: String?) {
    val uriHandler = LocalUriHandler.current
    SecondaryText(notice)
    if (termsUrl != null) {
        Text(
            text = "Gemma Terms of Use и Prohibited Use Policy",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { uriHandler.openUri(termsUrl) },
        )
    }
}

private fun sizeLabel(pack: OfflinePack): String =
    if (pack.installed && pack.totalSizeMb != null) "%.1f MB".format(pack.totalSizeMb) else "размер —"

private fun buildOfflinePacks(session: LiveSessionState): List<OfflinePack> {
    val asr = AsrCandidateRegistry.candidates.map { candidate ->
        val report = session.inspectAsrModel(candidate.id)
        OfflinePack(
            id = candidate.id,
            kind = "ASR · ${candidate.language.uppercase()}",
            tierLabel = candidate.deviceTiers.joinToString("/"),
            installed = report?.installed == true,
            totalSizeMb = report?.totalSizeMb,
        )
    }
    // Only on-device MT candidates with a concrete model id appear as installable offline packs;
    // cloud candidates (Gemini Flash) are not "packs". Install state is the REAL on-device report.
    val mt = MtCandidateRegistry.candidates
        .filter { it.execution == MtExecution.ON_DEVICE && it.modelId != null }
        .map { candidate ->
            val modelId = candidate.modelId ?: candidate.id
            val report = session.inspectMtModel(modelId)
            OfflinePack(
                id = modelId,
                kind = "MT · ${candidate.languagePairs.joinToString(", ")}",
                tierLabel = candidate.quality.label,
                installed = report?.installed == true,
                totalSizeMb = report?.totalSizeMb,
                licenseNotice = candidate.licenseNotice,
                licenseTermsUrl = candidate.licenseTermsUrl,
            )
        }
    return asr + mt
}
