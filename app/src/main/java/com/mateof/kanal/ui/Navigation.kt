package com.mateof.kanal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.mateof.kanal.R
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.mateof.kanal.ui.link.IncomingLinkViewModel
import com.mateof.kanal.ui.link.IncomingSourcesDialog
import com.mateof.kanal.ui.link.SendSourcesSheet
import com.mateof.kanal.ui.cast.CastBar
import com.mateof.kanal.ui.cast.CastBarViewModel
import com.mateof.kanal.ui.cast.CastPanelRequests
import com.mateof.kanal.ui.cast.CastPanelScreen
import com.mateof.kanal.ui.components.BottomNav
import com.mateof.kanal.ui.components.NavItem
import com.mateof.kanal.ui.components.NavRail
import com.mateof.kanal.ui.screens.detail.MovieDetailScreen
import com.mateof.kanal.ui.screens.detail.SeriesDetailScreen
import com.mateof.kanal.ui.screens.downloads.DownloadsScreen
import com.mateof.kanal.ui.screens.favorites.FavoritesScreen
import com.mateof.kanal.ui.screens.gate.GateScreen
import com.mateof.kanal.ui.screens.guide.GuideScreen
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
    const val GUIDE = "guide"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val FAVORITES = "favorites"
    const val DOWNLOADS = "downloads"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
    const val CAST = "cast"

    fun setup(sourceId: String = "") = "setup?sourceId=$sourceId"
    fun movieDetail(id: String) = "movie/$id"
    fun seriesDetail(id: String) = "serie/$id"

    /** kind is LIVE / MOVIE / SERIES; [start] is only used for catch-up. */
    fun player(kind: String, itemId: String, start: Long = 0L) = "player/$kind/$itemId?start=$start"
}

/** Saved-state key carrying the channel from the player back to the list. */
private const val RESUMED_CHANNEL = "resumed_channel"

private val RAIL_ITEMS = listOf(
    NavItem(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    NavItem(Routes.LIVE, R.string.nav_live, Icons.Outlined.LiveTv),
    NavItem(Routes.GUIDE, R.string.nav_guide, Icons.Outlined.CalendarMonth),
    NavItem(Routes.MOVIES, R.string.nav_movies, Icons.Outlined.Movie),
    NavItem(Routes.SERIES, R.string.nav_series, Icons.Outlined.Tv),
    NavItem(Routes.FAVORITES, R.string.nav_favorites, Icons.Outlined.Star),
    NavItem(Routes.DOWNLOADS, R.string.nav_downloads, Icons.Outlined.Download),
    NavItem(Routes.SEARCH, R.string.nav_search, Icons.Outlined.Search),
    NavItem(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings)
)

/** Only on the rail while something is on another device. */
private val CAST_ITEM = NavItem(Routes.CAST, R.string.nav_cast, Icons.Outlined.CastConnected)

@Composable
fun KanalNavHost() {
    val nav = rememberNavController()

    // The notification, or a sheet, asking for the transmission panel. Held
    // until the gate has let the app in: there is nothing to go back to before.
    val panelRequested by CastPanelRequests.pending.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route
    LaunchedEffect(panelRequested, currentRoute) {
        if (!panelRequested || currentRoute == null) return@LaunchedEffect
        if (currentRoute == Routes.GATE || currentRoute.startsWith(Routes.SETUP)) return@LaunchedEffect
        CastPanelRequests.consume()
        if (currentRoute != Routes.CAST) nav.navigate(Routes.CAST) { launchSingleTop = true }
    }

    // Another device with Kanal sent something to play, or took it back.
    val link: IncomingLinkViewModel = hiltViewModel()
    val incoming by link.play.collectAsStateWithLifecycle()
    val offer by link.offer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(incoming, currentRoute) {
        val play = incoming ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == Routes.GATE) return@LaunchedEffect
        link.consumePlay()
        Toast.makeText(
            context,
            context.getString(R.string.link_received, play.from, play.title),
            Toast.LENGTH_LONG
        ).show()
        // One player at a time: whatever was playing gives way to what arrived.
        if (currentRoute.startsWith("player/")) nav.popBackStack()
        nav.navigate(Routes.player(play.kind, play.itemId, play.start))
    }
    LaunchedEffect(Unit) {
        link.stops.collect {
            val route = nav.currentBackStackEntry?.destination?.route
            if (route?.startsWith("player/") == true) nav.popBackStack()
        }
    }
    // Sources that arrived on a device with none yet: straight in, as after
    // adding one by hand. Home syncs a source that has never been synced.
    val imported by link.imported.collectAsStateWithLifecycle()
    LaunchedEffect(imported, currentRoute) {
        if (!imported || currentRoute == null || currentRoute == Routes.GATE) return@LaunchedEffect
        link.consumeImported()
        if (currentRoute.startsWith(Routes.SETUP)) {
            nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
        }
    }

    Box(Modifier.fillMaxSize()) {
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

        composable(Routes.LIVE) { entry ->
            // The player hands the channel back through the saved state so the
            // list can keep it playing in the preview.
            val resumed by entry.savedStateHandle
                .getStateFlow<String?>(RESUMED_CHANNEL, null)
                .collectAsStateWithLifecycle()
            WithRail(nav, Routes.LIVE) {
                LiveScreen(
                    onPlay = { id, start ->
                        entry.savedStateHandle[RESUMED_CHANNEL] = null
                        nav.navigate(Routes.player("LIVE", id, start))
                    },
                    resumedChannelId = resumed,
                    onBack = {
                        entry.savedStateHandle[RESUMED_CHANNEL] = null
                        if (!nav.popBackStack()) nav.navigateTop(Routes.HOME)
                    }
                )
            }
        }

        composable(Routes.GUIDE) {
            WithRail(nav, Routes.GUIDE) {
                GuideScreen(onPlay = { id -> nav.navigate(Routes.player("LIVE", id)) })
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

        composable(Routes.DOWNLOADS) {
            WithRail(nav, Routes.DOWNLOADS) {
                DownloadsScreen(
                    // Same route as anywhere else: playback finds the local file
                    // by itself, so nothing here needs to know about files.
                    onPlay = { kind, id -> nav.navigate(Routes.player(kind, id)) }
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
                Box(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        onAddSource = { nav.navigate(Routes.setup()) },
                        onEditSource = { id -> nav.navigate(Routes.setup(id)) },
                        onOpenLogs = { nav.navigate(Routes.LOGS) }
                    )
                    // Same view model as the card that opens it: one per screen.
                    SendSourcesSheet()
                }
            }
        }

        composable(Routes.LOGS) {
            LogsScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.CAST) {
            CastPanelScreen(
                onBack = { if (!nav.popBackStack()) nav.navigateTop(Routes.HOME) },
                onWatchHere = { kind, itemId ->
                    // A player underneath sent it, and takes the stream back
                    // by itself once the television stops.
                    val below = nav.previousBackStackEntry?.destination?.route
                    if (below?.startsWith("player/") == true) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.player(kind, itemId)) { popUpTo(Routes.CAST) { inclusive = true } }
                    }
                }
            )
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
                onBack = { liveChannelId ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(RESUMED_CHANNEL, liveChannelId)
                    // Sent from another device onto the setup screen: there is
                    // nothing behind it but setup, which is where it goes.
                    if (!nav.popBackStack()) nav.navigate(Routes.GATE)
                }
            )
        }
    }
    offer?.let { pending ->
        IncomingSourcesDialog(pending, onAnswer = { accept -> link.answer(pending, accept) })
    }
    }
}

@Composable
private fun WithRail(
    nav: NavHostController,
    selected: String,
    content: @Composable () -> Unit
) {
    val castVm: CastBarViewModel = hiltViewModel()
    val cast by castVm.session.collectAsStateWithLifecycle()
    val openPanel = { nav.navigate(Routes.CAST) { launchSingleTop = true } }
    val onSelect: (String) -> Unit = { route ->
        when (route) {
            Routes.CAST -> openPanel()
            selected -> Unit
            else -> nav.navigateTop(route)
        }
    }

    if (isCompact) {
        // Upright on a phone there is no room beside the content, so the
        // destinations move to the bottom edge, within thumb reach.
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            // Nine destinations already fill the bottom bar; the transmission
            // gets a strip of its own, which also has room for the speed.
            cast?.let { CastBar(it, onOpen = openPanel) }
            BottomNav(RAIL_ITEMS, selected, onSelect)
        }
    } else {
        // Where the arrow keys land when they leave the rail. Without this,
        // going right from a destination low down the rail — Ajustes, say —
        // was answered geometrically: Compose looked straight to the right at
        // that height, found whatever row happened to be there, and dragged
        // the page down to it. Handing the whole content area a single entry
        // point makes right mean "into the content", always at the same place.
        val contentEntry = remember { FocusRequester() }
        Row(Modifier.fillMaxSize()) {
            NavRail(
                items = if (cast != null) RAIL_ITEMS + CAST_ITEM else RAIL_ITEMS,
                selectedRoute = selected,
                onSelect = onSelect,
                modifier = Modifier.focusProperties { right = contentEntry }
            )
            // A group, but deliberately not a focusRestorer: enter and exit are
            // inherited by every focus group inside, so a restorer here answers
            // the move into the channel list with "back to the chip you came
            // from" and the arrows stop working halfway down the screen.
            // Remembering the place belongs to each row, not to the whole page.
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentEntry)
                    .focusGroup()
            ) { content() }
        }
    }
}

/** Top-level tabs replace each other instead of stacking up. */
private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { inclusive = route == Routes.HOME }
        launchSingleTop = true
    }
}
