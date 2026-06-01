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
import dev.flextranslate.ui.components.Badge
import dev.flextranslate.ui.components.BadgeTone
import dev.flextranslate.ui.screens.CloudScreen
import dev.flextranslate.ui.screens.DiagnosticsScreen
import dev.flextranslate.ui.screens.LanguagesScreen
import dev.flextranslate.ui.screens.LiveScreen
import dev.flextranslate.ui.screens.ModelsScreen
import dev.flextranslate.ui.theme.Bg

private data class NavDestination(val title: String, val icon: ImageVector)

private val destinations = listOf(
    NavDestination("Эфир", Icons.Default.GraphicEq),
    NavDestination("Языки", Icons.Default.Translate),
    NavDestination("Модели", Icons.Default.Download),
    NavDestination("Облако", Icons.Default.Cloud),
    NavDestination("Диагностика", Icons.Default.Speed),
)

/**
 * Root navigation shell: TopAppBar (accent bold "Flex Translate") + global demo banner pill +
 * bottom NavigationBar (5 items) + when(currentTab) body. No nav graph (mirrors aquacard
 * MainAppScreen). The demo banner is the structural no-false-claims affordance on every tab.
 *
 * @param onRequestPermission routes Live's blocked-capture state to the host RECORD_AUDIO request.
 */
@Composable
fun AppScaffold(
    session: LiveSessionState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    2 -> ModelsScreen(session = session)
                    3 -> CloudScreen(session = session)
                    else -> DiagnosticsScreen(session = session)
                }
            }
        }
    }
}

/** Thin amber-tinted pill pinned under the top bar on every tab — the honest demo affordance. */
@Composable
private fun DemoBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Badge(text = "Demo · launch-support не заявлен", tone = BadgeTone.AMBER)
    }
}
