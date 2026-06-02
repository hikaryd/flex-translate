@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.flextranslate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.flextranslate.foundation.ModelDownloadManager
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.i18n.AppLanguage
import dev.flextranslate.ui.i18n.LocalStrings
import dev.flextranslate.ui.screens.CloudScreen
import dev.flextranslate.ui.screens.DiagnosticsScreen
import dev.flextranslate.ui.screens.LanguagesScreen
import dev.flextranslate.ui.screens.LiveScreen
import dev.flextranslate.ui.screens.ModelsScreen
import dev.flextranslate.ui.theme.Bg

private data class NavDestination(val title: String, val icon: ImageVector)

/**
 * Корневая оболочка навигации: TopAppBar (акцентный жирный «Flex Translate») + глобальная плашка
 * demo + нижний NavigationBar (5 пунктов) + тело through when(currentTab). Без nav-графа (как в
 * aquacard MainAppScreen). Плашка demo — структурный приём «не врать» на каждой вкладке.
 *
 * @param onRequestPermission прокидывает заблокированный захват с «Эфира» в хостовый запрос RECORD_AUDIO.
 * @param onLanguageChange    вызывается, когда пользователь меняет язык интерфейса на вкладке «Облако».
 * @param selectedLanguage    текущий активный язык интерфейса (для тумблера на вкладке «Облако»).
 */
@Composable
fun AppScaffold(
    session: LiveSessionState,
    downloadManager: ModelDownloadManager,
    onRequestPermission: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    selectedLanguage: AppLanguage,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // Строим пункты навигации из активных строк, чтобы подписи вкладок мгновенно меняли язык.
    val destinations = remember(s) {
        listOf(
            NavDestination(s.tabLive, Icons.Default.GraphicEq),
            NavDestination(s.tabLanguages, Icons.Default.Translate),
            NavDestination(s.tabModels, Icons.Default.Download),
            NavDestination(s.tabCloud, Icons.Default.Cloud),
            NavDestination(s.tabDiagnostics, Icons.Default.Speed),
        )
    }

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        modifier = modifier,
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Flex Translate",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = { Icon(destination.icon, contentDescription = destination.title) },
                        label = { Text(destination.title) },
                    )
                }
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            DemoBanner()
            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    0 -> LiveScreen(session = session, onRequestPermission = onRequestPermission)
                    1 -> LanguagesScreen(session = session)
                    2 -> ModelsScreen(session = session, downloadManager = downloadManager)
                    3 -> CloudScreen(
                        session = session,
                        selectedLanguage = selectedLanguage,
                        onLanguageChange = onLanguageChange,
                    )
                    else -> DiagnosticsScreen(session = session)
                }
            }
        }
    }
}

/** Тонкая amber-плашка под верхней панелью на каждой вкладке — честный demo-маркер. */
@Composable
private fun DemoBanner() {
    val s = LocalStrings.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Badge(text = s.demoBanner, tone = BadgeTone.AMBER)
    }
}
