package ru.slovarik.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.slovarik.app.ui.folders.FolderDetailScreen
import ru.slovarik.app.ui.folders.FoldersScreen
import ru.slovarik.app.ui.home.HomeScreen
import ru.slovarik.app.ui.search.SearchScreen
import ru.slovarik.app.ui.sets.CardEditScreen
import ru.slovarik.app.ui.sets.ImportScreen
import ru.slovarik.app.ui.sets.SetDetailScreen
import ru.slovarik.app.ui.sets.SetEditScreen
import ru.slovarik.app.ui.settings.SettingsScreen
import ru.slovarik.app.ui.stats.StatsScreen
import ru.slovarik.app.ui.study.FlashcardsScreen
import ru.slovarik.app.ui.study.LearnScreen
import ru.slovarik.app.ui.study.MatchScreen
import ru.slovarik.app.ui.study.TestScreen

/** Ссылка на карточку из виджета. */
data class CardLink(val setId: Long, val cardId: Long)

/** Адреса экранов. */
object Routes {
    const val HOME = "home"
    const val FOLDERS = "folders"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val SEARCH = "search"

    const val ARG_SET = "setId"
    const val ARG_FOLDER = "folderId"
    const val ARG_CARD = "cardId"

    /** Идентификатор «набора» для режима заучивания по всем словам, которые пора повторить. */
    const val DUE_SET_ID = 0L

    fun folder(id: Long) = "folder/$id"
    fun set(id: Long) = "set/$id"
    fun setEdit(setId: Long = -1, folderId: Long = -1) = "setEdit?setId=$setId&folderId=$folderId"
    fun cardEdit(setId: Long, cardId: Long = -1) = "cardEdit/$setId?cardId=$cardId"
    fun import(setId: Long) = "import/$setId"
    fun flashcards(setId: Long, cardId: Long = -1) = "flashcards/$setId?cardId=$cardId"
    fun learn(setId: Long) = "learn/$setId"
    fun test(setId: Long) = "test/$setId"
    fun match(setId: Long) = "match/$setId"
}

private data class BottomItem(val route: String, val title: String, val icon: ImageVector)

private val bottomItems = listOf(
    BottomItem(Routes.HOME, "Главная", Icons.Filled.Home),
    BottomItem(Routes.FOLDERS, "Папки", Icons.Filled.Folder),
    BottomItem(Routes.STATS, "Статистика", Icons.Filled.BarChart),
    BottomItem(Routes.SETTINGS, "Настройки", Icons.Filled.Settings),
)

private fun longArg(name: String) = navArgument(name) { type = NavType.LongType }

private fun optionalLongArg(name: String) = navArgument(name) {
    type = NavType.LongType
    defaultValue = -1L
}

@Composable
fun AppRoot(pendingLink: CardLink?, onLinkConsumed: () -> Unit) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(pendingLink) {
        if (pendingLink != null) {
            nav.navigate(Routes.flashcards(pendingLink.setId, pendingLink.cardId))
            onLinkConsumed()
        }
    }

    Scaffold(
        // системные отступы обрабатывают сами экраны, поэтому здесь они отключены
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (bottomItems.any { it.route == currentRoute }) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() },
        ) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.FOLDERS) { FoldersScreen(nav) }
            composable(Routes.STATS) { StatsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.SEARCH) { SearchScreen(nav) }

            composable("folder/{folderId}", arguments = listOf(longArg(Routes.ARG_FOLDER))) {
                FolderDetailScreen(nav)
            }
            composable("set/{setId}", arguments = listOf(longArg(Routes.ARG_SET))) {
                SetDetailScreen(nav)
            }
            composable(
                "setEdit?setId={setId}&folderId={folderId}",
                arguments = listOf(optionalLongArg(Routes.ARG_SET), optionalLongArg(Routes.ARG_FOLDER)),
            ) {
                SetEditScreen(nav)
            }
            composable(
                "cardEdit/{setId}?cardId={cardId}",
                arguments = listOf(longArg(Routes.ARG_SET), optionalLongArg(Routes.ARG_CARD)),
            ) {
                CardEditScreen(nav)
            }
            composable("import/{setId}", arguments = listOf(longArg(Routes.ARG_SET))) {
                ImportScreen(nav)
            }
            composable(
                "flashcards/{setId}?cardId={cardId}",
                arguments = listOf(longArg(Routes.ARG_SET), optionalLongArg(Routes.ARG_CARD)),
            ) {
                FlashcardsScreen(nav)
            }
            composable("learn/{setId}", arguments = listOf(longArg(Routes.ARG_SET))) {
                LearnScreen(nav)
            }
            composable("test/{setId}", arguments = listOf(longArg(Routes.ARG_SET))) {
                TestScreen(nav)
            }
            composable("match/{setId}", arguments = listOf(longArg(Routes.ARG_SET))) {
                MatchScreen(nav)
            }
        }
    }
}
