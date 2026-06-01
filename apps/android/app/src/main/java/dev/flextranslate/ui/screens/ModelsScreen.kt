package dev.flextranslate.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.AsrCandidateRegistry
import dev.flextranslate.foundation.ModelDownloadManager
import dev.flextranslate.foundation.ModelDownloadManager.DownloadState
import dev.flextranslate.foundation.ModelDownloadSpecs
import dev.flextranslate.foundation.MtCandidateRegistry
import dev.flextranslate.foundation.MtExecution
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.components.SecondaryText
import dev.flextranslate.ui.components.SectionCard

private const val BYTES_PER_MB = 1024.0 * 1024.0

/** A single offline pack row, derived honestly from the candidate registries + on-device store. */
private data class OfflinePack(
    val id: String,
    val kind: String,
    val tierLabel: String,
    val installed: Boolean,
    val totalSizeMb: Double?,
    /** Advertised download size (MB) from the pack's download spec, shown before install. */
    val downloadSizeMb: Double?,
    /** True when this pack has a real download source wired up. */
    val downloadable: Boolean,
    /**
     * License pass-through for restricted on-device models (Gemma-derived MiLMMT): the notice text
     * the app MUST surface and the terms URL the user accepts. Null for permissive packs.
     */
    val licenseNotice: String? = null,
    val licenseTermsUrl: String? = null,
)

/**
 * Модели / Models & offline packs — manage offline ASR/MT packs honestly. Weights are NOT
 * bundled; they are acquired by a REAL in-app download ([ModelDownloadManager]) into the same
 * `models/<id>/` root the runtime loads from. Each pack shows its REAL on-device install state and
 * size; downloads show live progress and verify SHA-256 before flipping to "установлен". Nothing is
 * marked "supported" — that still requires WS6 benchmark evidence.
 */
@Composable
fun ModelsScreen(
    session: LiveSessionState,
    downloadManager: ModelDownloadManager,
    modifier: Modifier = Modifier,
) {
    // Bumped after a terminal download/delete so install-state-derived rows recompute.
    var refreshTick by remember { mutableIntStateOf(0) }
    val online = downloadManager.isOnline()
    val packs = remember(session, refreshTick) { buildOfflinePacks(session) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(radius = 12, title = "Offline-пакеты") {
                SecondaryText(
                    "Веса моделей не входят в сборку (лицензия/размер) — они скачиваются в приложении " +
                        "по сети с проверкой контрольной суммы. Установленные пакеты показывают реальный " +
                        "размер на устройстве. Поддержка не заявляется без benchmark-доказательств.",
                )
            }
        }
        items(packs, key = { it.id }) { pack ->
            PackRow(
                pack = pack,
                isOnline = online,
                downloadManager = downloadManager,
                onTerminal = { refreshTick++ },
            )
        }
    }
}

@Composable
private fun PackRow(
    pack: OfflinePack,
    isOnline: Boolean,
    downloadManager: ModelDownloadManager,
    onTerminal: () -> Unit,
) {
    val downloadState by downloadManager.state(pack.id)
    // A download just completed → re-inspect install state once.
    if (downloadState is DownloadState.Done && !pack.installed) onTerminal()
    val installed = pack.installed || downloadState is DownloadState.Done

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
                text = sizeLabel(pack, installed),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val state = downloadState) {
            is DownloadState.Downloading -> DownloadProgress(state, pack.id, downloadManager)
            else -> PackActionRow(pack, installed, isOnline, state, downloadManager, onTerminal)
        }

        StatusLine(pack, installed, isOnline, downloadState)

        // Gemma pass-through: the license REQUIRES surfacing the terms + prohibited-use policy to
        // the user for the MiLMMT (Gemma-derived) pack. Shown for both states (informs before
        // download and reminds when installed). Tapping the link opens the Gemma terms.
        pack.licenseNotice?.let { notice ->
            LicenseDisclosure(notice = notice, termsUrl = pack.licenseTermsUrl)
        }
    }
}

@Composable
private fun DownloadProgress(
    state: DownloadState.Downloading,
    modelId: String,
    downloadManager: ModelDownloadManager,
) {
    val percent = (state.fraction * 100f).toInt()
    val doneMb = state.bytesDone / BYTES_PER_MB
    val totalMb = state.bytesTotal / BYTES_PER_MB
    LinearProgressIndicator(
        progress = { state.fraction },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%d%% · %.1f / %.1f MB".format(percent, doneMb, totalMb),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { downloadManager.cancel(modelId) }) { Text("Отмена") }
    }
    state.currentFile?.let { file ->
        SecondaryText("Загрузка: $file")
    }
}

@Composable
private fun PackActionRow(
    pack: OfflinePack,
    installed: Boolean,
    isOnline: Boolean,
    downloadState: DownloadState,
    downloadManager: ModelDownloadManager,
    onTerminal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (installed) {
            Badge(text = "установлен", tone = BadgeTone.GREEN)
            OutlinedButton(onClick = {
                downloadManager.delete(pack.id)
                onTerminal()
            }) { Text("Удалить") }
        } else {
            Badge(text = downloadStatusBadge(downloadState), tone = downloadStatusTone(downloadState))
            val retry = downloadState is DownloadState.Failed || downloadState is DownloadState.Cancelled
            OutlinedButton(
                onClick = { downloadManager.start(pack.id) },
                enabled = isOnline && pack.downloadable,
            ) { Text(if (retry) "Повторить" else "Скачать") }
        }
    }
}

@Composable
private fun StatusLine(
    pack: OfflinePack,
    installed: Boolean,
    isOnline: Boolean,
    downloadState: DownloadState,
) {
    when {
        downloadState is DownloadState.Downloading -> Unit
        installed -> SecondaryText("Готов к локальному распознаванию (demo, качество не проверено).")
        downloadState is DownloadState.Failed ->
            SecondaryText("Ошибка: ${downloadState.message}. Файл проверяется по SHA-256; повтор продолжит докачку.")
        downloadState is DownloadState.Cancelled ->
            SecondaryText("Загрузка отменена. Частичный файл сохранён — повтор продолжит докачку.")
        !pack.downloadable -> SecondaryText("Источник загрузки пока не настроен для этого пакета.")
        !isOnline -> SecondaryText("Доступно только онлайн. Контрольная сумма проверяется после загрузки.")
        else -> SecondaryText("Скачивается по сети, проверяется по SHA-256, затем доступно офлайн.")
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

private fun downloadStatusBadge(state: DownloadState): String = when (state) {
    is DownloadState.Failed -> "ошибка"
    is DownloadState.Cancelled -> "отменено"
    else -> "не установлен"
}

private fun downloadStatusTone(state: DownloadState): BadgeTone = when (state) {
    is DownloadState.Failed -> BadgeTone.RED
    is DownloadState.Cancelled -> BadgeTone.AMBER
    else -> BadgeTone.NEUTRAL
}

private fun sizeLabel(pack: OfflinePack, installed: Boolean): String = when {
    installed && pack.totalSizeMb != null -> "%.1f MB".format(pack.totalSizeMb)
    pack.downloadSizeMb != null -> "≈%.1f MB".format(pack.downloadSizeMb)
    else -> "размер —"
}

private fun buildOfflinePacks(session: LiveSessionState): List<OfflinePack> {
    val asr = AsrCandidateRegistry.candidates.map { candidate ->
        val report = session.inspectAsrModel(candidate.id)
        val downloadSpec = ModelDownloadSpecs.forModelId(candidate.id)
        OfflinePack(
            id = candidate.id,
            kind = "ASR · ${candidate.language.uppercase()}",
            tierLabel = candidate.deviceTiers.joinToString("/"),
            installed = report?.installed == true,
            totalSizeMb = report?.totalSizeMb,
            downloadSizeMb = downloadSpec?.totalMb,
            downloadable = downloadSpec != null,
        )
    }
    // Only on-device MT candidates with a concrete model id appear as installable offline packs;
    // cloud candidates (Gemini Flash) are not "packs". Install state is the REAL on-device report.
    val mt = MtCandidateRegistry.candidates
        .filter { it.execution == MtExecution.ON_DEVICE && it.modelId != null }
        .map { candidate ->
            val modelId = candidate.modelId ?: candidate.id
            val report = session.inspectMtModel(modelId)
            val downloadSpec = ModelDownloadSpecs.forModelId(modelId)
            OfflinePack(
                id = modelId,
                kind = "MT · ${candidate.languagePairs.joinToString(", ")}",
                tierLabel = candidate.quality.label,
                installed = report?.installed == true,
                totalSizeMb = report?.totalSizeMb,
                downloadSizeMb = downloadSpec?.totalMb,
                downloadable = downloadSpec != null,
                licenseNotice = candidate.licenseNotice,
                licenseTermsUrl = candidate.licenseTermsUrl,
            )
        }
    return asr + mt
}
