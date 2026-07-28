package com.mateof.kanal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mateof.kanal.ui.components.NavItem
import com.mateof.kanal.ui.components.NavRail
import com.mateof.kanal.ui.screens.detail.MovieDetailScreen
import com.mateof.kanal.ui.screens.detail.SeriesDetailScreen
import com.mateof.kanal.ui.screens.favorites.FavoritesScreen
import com.mateof.kanal.ui.screens.gate.GateScreen
import com.mateof.kanal.ui.screens.home.HomeScreen
import com.mateof.kanal.ui.screens.live.LiveScreen
import com.mateof.kanal.ui.screens.logs.LogsScreen
import com.mateof.kanal.ui.screens.player.PlayerScreen
import com.mateof.kanal.ui.screens.search.SearchScreen
import com.mateof.kanal.ui.screens.settings.SettingsScreen
import com.mateof.kanal.ui.screens.setup.SetupScreen
import com.mateof.kanal.ui.screens.vod.MoviesScreen
import com.mateof.kanal.ui.screens.vod.SeriesScreen

object Routes {
    const val GATE = "gate"
    const val SETUP = "setup"
    const val HOME = "home"
    const val LIVE = "live"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val FAVORITES = "favorites"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LOGS = "logs"

    fun setup(sourceId: String = "") = "setup?sourceId=$sourceId"
    fun movieDetail(id: String) = "movie/$id"
    fun seriesDetail(id: String) = "serie/$id"

    /** kind is LIVE / MOVIE / SERIES; [start] is only used for catch-up. */
    fun player(kind: String, itemId: String, start: Long = 0L) = "player/$kind/$itemId?start=$start"
}

private val RAIL_ITEMS = listOf(
    NavItem(Routes.HOME, "Inicio", Icons.Outlined.Home),
    NavItem(Routes.LIVE, "TV en directo", Icons.Outlined.LiveTv),
    NavItem(Routes.MOVIES, "Películas", Icons.Outlined.Movie),
    NavItem(Routes.SERIES, "Series", Icons.Outlined.Tv),
    NavItem(Routes.FAVORITES, "Favoritos", Icons.Outlined.Star),
    NavItem(Routes.SEARCH, "Buscar", Icons.Outlined.Search),
    NavItem(Routes.SETTINGS, "Ajustes", Icons.Outlined.Settings)
)

@Composable
fun KanalNavHost() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.GATE) {
        composable(Routes.GATE) {
            GateScreen(
                onNeedsSetup = { nav.navigate(Routes.setup()) { popUpTo(Routes.GATE) { inclusive = true } } },
                onReady = { nav.navigate(Routes.HOME) { popUpTo(Routes.GATE) { inclusive = true } } }
            )
        }

        composable(
            route = "setup?sourceId={sourceId}",
            arguments = listOf(navArgument("sourceId") { defaultValue = "" })
        ) { entry ->
            SetupScreen(
                sourceId = entry.arguments?.getString("sourceId").orEmpty(),
                onDone = {
                    nav.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onCancel = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) }
            )
        }

        composable(Routes.HOME) {
            WithRail(nav, Routes.HOME) {
                HomeScreen(
                    onOpenChannel = { id -> nav.navigate(Routes.player("LIVE", id)) },
                    onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                    onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
                    onResume = { kind, id -> nav.navigate(Routes.player(kind, id)) },
                    onNavigate = { route -> nav.navigateTop(route) }
                )
            }
        }

        composable(Routes.LIVE) {
            WithRail(nav, Routes.LIVE) {
                LiveScreen(onPlay = { id, start -> nav.navigate(Routes.player("LIVE", id, start)) })
            }
        }

        composable(Routes.MOVIES) {
            WithRail(nav, Routes.MOVIES) {
                MoviesScreen(onOpen = { id -> nav.navigate(Routes.movieDetail(id)) })
            }
        }

        composable(Routes.SERIES) {
            WithRail(nav, Routes.SERIES) {
                SeriesScreen(onOpen = { id -> nav.navigate(Routes.seriesDetail(id)) })
            }
        }

        composable(Routes.FAVORITES) {
            WithRail(nav, Routes.FAVORITES) {
                FavoritesScreen(
                    onOpenChannel = { id -> nav.navigate(Routes.player("LIVE", id)) },
                    onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                    onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) }
                )
            }
        }

        composable(Routes.SEARCH) {
            WithRail(nav, Routes.SEARCH) {
                SearchScreen(
                    onOpenChannel = { id -> nav.navigate(Routes.player("LIVE", id)) },
                    onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                    onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) }
                )
            }
        }

        composable(Routes.SETTINGS) {
            WithRail(nav, Routes.SETTINGS) {
                SettingsScreen(
                    onAddSource = { nav.navigate(Routes.setup()) },
                    onEditSource = { id -> nav.navigate(Routes.setup(id)) },
                    onOpenLogs = { nav.navigate(Routes.LOGS) }
                )
            }
        }

        composable(Routes.LOGS) {
            LogsScreen(onBack = { nav.popBackStack() })
        }

        composable(
            route = "movie/{movieId}",
            arguments = listOf(navArgument("movieId") { type = NavType.StringType })
        ) { entry ->
            MovieDetailScreen(
                movieId = entry.arguments?.getString("movieId").orEmpty(),
                onPlay = { id -> nav.navigate(Routes.player("MOVIE", id)) },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = "serie/{seriesId}",
            arguments = listOf(navArgument("seriesId") { type = NavType.StringType })
        ) { entry ->
            SeriesDetailScreen(
                seriesId = entry.arguments?.getString("seriesId").orEmpty(),
                onPlayEpisode = { id -> nav.navigate(Routes.player("SERIES", id)) },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = "player/{kind}/{itemId}?start={start}",
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("itemId") { type = NavType.StringType },
                navArgument("start") { type = NavType.LongType; defaultValue = 0L }
            )
        ) { entry ->
            PlayerScreen(
                kind = entry.arguments?.getString("kind").orEmpty(),
                itemId = entry.arguments?.getString("itemId").orEmpty(),
                startMillis = entry.arguments?.getLong("start") ?: 0L,
                onBack = { nav.popBackStack() }
            )
        }
    }
}

@Composable
private fun WithRail(
    nav: NavHostController,
    selected: String,
    content: @Composable () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        NavRail(
            items = RAIL_ITEMS,
            selectedRoute = selected,
            onSelect = { route -> if (route != selected) nav.navigateTop(route) }
        )
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Top-level tabs replace each other instead of stacking up. */
private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { inclusive = route == Routes.HOME }
        launchSingleTop = true
    }
}
