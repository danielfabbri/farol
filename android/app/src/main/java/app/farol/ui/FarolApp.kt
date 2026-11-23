package app.farol.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.farol.ui.screens.ArchiveDayScreen
import app.farol.ui.screens.ArticleScreen
import app.farol.ui.screens.ExploreScreen
import app.farol.ui.screens.SavedScreen
import app.farol.ui.screens.SettingsScreen
import app.farol.ui.screens.TodayScreen
import app.farol.ui.screens.TrendsScreen

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel("today", "Hoje", Icons.Outlined.Today),
    TopLevel("explore", "Explorar", Icons.Outlined.Explore),
    TopLevel("trends", "Tendências", Icons.AutoMirrored.Outlined.TrendingUp),
    TopLevel("saved", "Salvos", Icons.Outlined.BookmarkBorder),
    TopLevel("settings", "Ajustes", Icons.Outlined.Settings),
)

private fun NavHostController.goTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun FarolApp(vm: MainViewModel, state: UiState) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val archive by vm.archive.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openArticle: (String) -> Unit = { id -> nav.navigate("article/$id") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (topLevel.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = { nav.goTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding)) {
            composable("today") {
                TodayScreen(
                    state,
                    onRefresh = { vm.refresh(force = true) },
                    onOpenArticle = openArticle,
                    onOpenExplore = { nav.goTopLevel("explore") },
                    onReadFilter = vm::setReadFilter,
                )
            }
            composable("explore") {
                ExploreScreen(state, onOpenArticle = openArticle, onOpenDay = { day -> nav.navigate("archive/$day") }, onReadFilter = vm::setReadFilter)
            }
            composable("trends") { TrendsScreen(state, onOpenArticle = openArticle) }
            composable("saved") { SavedScreen(state, onOpenArticle = openArticle) }
            composable("settings") {
                SettingsScreen(
                    state,
                    onRefresh = { vm.refresh(force = true) },
                    onTheme = vm::setTheme,
                    onHighlightLimit = vm::setHighlightLimit,
                    onOtherLimit = vm::setOtherLimit,
                    onDemoMode = vm::setDemoMode,
                    onClearCache = { vm.clearCache() },
                    onSaveToken = { vm.setGithubToken(it) },
                    onScan = { vm.scanNow() },
                    onDismissScan = { vm.dismissScanMessage() },
                    update = update,
                    onCheckUpdate = { vm.checkForUpdate() },
                    onDownloadUpdate = { vm.downloadUpdate() },
                    onInstallUpdate = { vm.installIntent()?.let { context.startActivity(it) } },
                )
            }
            composable("article/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val article = vm.findArticle(id)
                val event = article?.eventId?.let { state.eventsById[it] }
                ArticleScreen(
                    article = article,
                    event = event,
                    subcategoryLabel = article?.let { state.labelFor(it) },
                    saved = id in state.savedIds,
                    onBack = { nav.popBackStack() },
                    onToggleSave = { vm.toggleSave(it) },
                    onMarkRead = { vm.markRead(it) },
                )
            }
            composable("archive/{day}") { entry ->
                val day = entry.arguments?.getString("day").orEmpty()
                ArchiveDayScreen(
                    day = day,
                    archive = archive[day],
                    state = state,
                    onLoad = vm::loadArchive,
                    onBack = { nav.popBackStack() },
                    onOpenArticle = openArticle,
                )
            }
        }
    }
}
