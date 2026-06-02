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
import dev.flextranslate.ui.i18n.LocalStrings

private const val BYTES_PER_MB = 1024.0 * 1024.0

/** Строка одного офлайн-пака; собирается честно из реестров кандидатов и стора на устройстве. */
private data class OfflinePack(
    val id: String,
    val kind: String,
    val tierLabel: String,
    val installed: Boolean,
    val totalSizeMb: Double?,
    /** Заявленный размер загрузки (МБ) из download-спеки пака; показываем до установки. */
    val downloadSizeMb: Double?,
    /** true, если у пака реально настроен источник загрузки. */
    val downloadable: Boolean,
    /**
     * Проброс лицензии для ограниченных on-device моделей (MiLMMT на базе Gemma): текст уведомления,
     * который приложение ОБЯЗАНО показать, и URL условий, которые принимает пользователь.
     * Для пермиссивных паков — null.
     */
    val licenseNotice: String? = null,
    val licenseTermsUrl: String? = null,
)

/**
 * Экран «Модели» / офлайн-паки — честное управление офлайн-паками ASR/MT. Веса НЕ зашиты в APK;
 * их тянет НАСТОЯЩАЯ in-app загрузка ([ModelDownloadManager]) в тот же корень `models/<id>/`,
 * откуда грузит рантайм. Каждый пак показывает РЕАЛЬНОЕ состояние установки и размер на устройстве;
 * загрузка идёт с живым прогрессом и сверкой SHA-256 перед тем, как переключиться в «установлено».
 * Ничего не помечаем как «поддерживается» — для этого нужны бенчмарки WS6.
 */
@Composable
fun ModelsScreen(
    session: LiveSessionState,
    downloadManager: ModelDownloadManager,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // Дёргаем после завершённой загрузки/удаления, чтобы строки, зависящие от состояния установки, пересчитались.
    var refreshTick by remember { mutableIntStateOf(0) }
    val online = downloadManager.isOnline()
    val packs = remember(session, refreshTick) { buildOfflinePacks(session) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(radius = 12, title = s.offlinePacksTitle) {
                SecondaryText(s.offlinePacksHeader)
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
    // Загрузка только что завершилась → один раз перепроверяем состояние установки.
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

        // Проброс Gemma: лицензия ТРЕБУЕТ показать пользователю условия и политику запрещённого
        // использования для пака MiLMMT (на базе Gemma). Показываем в обоих состояниях — и до
        // загрузки (информируем), и после установки (напоминаем). Тап по ссылке открывает условия Gemma.
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
    val s = LocalStrings.current
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
        TextButton(onClick = { downloadManager.cancel(modelId) }) { Text(s.cancel) }
    }
    state.currentFile?.let { file ->
        SecondaryText(s.downloadingFile(file))
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
    val s = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (installed) {
            Badge(text = s.installed, tone = BadgeTone.GREEN)
            OutlinedButton(onClick = {
                downloadManager.delete(pack.id)
                onTerminal()
            }) { Text(s.delete) }
        } else {
            Badge(text = downloadStatusBadge(downloadState, s), tone = downloadStatusTone(downloadState))
            val retry = downloadState is DownloadState.Failed || downloadState is DownloadState.Cancelled
            OutlinedButton(
                onClick = { downloadManager.start(pack.id) },
                enabled = isOnline && pack.downloadable,
            ) { Text(if (retry) s.retry else s.download) }
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
    val s = LocalStrings.current
    when {
        downloadState is DownloadState.Downloading -> Unit
        installed -> SecondaryText(s.installedStatusLine)
        downloadState is DownloadState.Failed ->
            SecondaryText(s.downloadFailedLine(downloadState.message))
        downloadState is DownloadState.Cancelled ->
            SecondaryText(s.downloadCancelledLine)
        !pack.downloadable -> SecondaryText(s.sourceNotConfiguredLine)
        !isOnline -> SecondaryText(s.onlineOnlyLine)
        else -> SecondaryText(s.downloadsOverNetworkLine)
    }
}

@Composable
private fun LicenseDisclosure(notice: String, termsUrl: String?) {
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    SecondaryText(notice)
    if (termsUrl != null) {
        Text(
            text = s.gemmaTermsLink,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { uriHandler.openUri(termsUrl) },
        )
    }
}

private fun downloadStatusBadge(
    state: DownloadState,
    s: dev.flextranslate.ui.i18n.Strings,
): String = when (state) {
    is DownloadState.Failed -> s.statusError
    is DownloadState.Cancelled -> s.statusCancelled
    else -> s.statusNotInstalled
}

private fun downloadStatusTone(state: DownloadState): BadgeTone = when (state) {
    is DownloadState.Failed -> BadgeTone.RED
    is DownloadState.Cancelled -> BadgeTone.AMBER
    else -> BadgeTone.NEUTRAL
}

private fun sizeLabel(pack: OfflinePack, installed: Boolean): String = when {
    installed && pack.totalSizeMb != null -> "%.1f MB".format(pack.totalSizeMb)
    pack.downloadSizeMb != null -> "≈%.1f MB".format(pack.downloadSizeMb)
    else -> "—"   // нейтральный прочерк; локализованный sizeUnknown берут те, кому он нужен
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
    // Как устанавливаемые офлайн-паки показываем только on-device MT-кандидатов с конкретным model id;
    // облачные кандидаты (Gemini Flash) — не «паки». Состояние установки — РЕАЛЬНЫЙ отчёт с устройства.
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
