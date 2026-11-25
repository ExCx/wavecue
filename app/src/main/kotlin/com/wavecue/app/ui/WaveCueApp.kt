package com.wavecue.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wavecue.app.ui.library.LibraryScreen
import com.wavecue.app.ui.player.PlayerScreen
import com.wavecue.app.ui.playlists.PlaylistDetailScreen
import com.wavecue.app.ui.playlists.PlaylistsScreen
import com.wavecue.app.ui.settings.SettingsScreen
import com.wavecue.app.ui.theme.WaveCueColors
import com.wavecue.core.model.PlaybackState

object Routes {
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    fun playlistDetail(id: Long) = "playlist/$id"
}

@Composable
fun WaveCueApp() {
    val navController = rememberNavController()
    val viewModel: AppViewModel = hiltViewModel()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val playbackState by viewModel.state.collectAsStateWithLifecycle()
    val media by viewModel.media.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val queueLabel by viewModel.queueLabel.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { message -> snackbar.showSnackbar(message) }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    fun openTab(target: String) {
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = WaveCueColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (current != null) {
                    NowPlayingBar(
                        title = media?.name ?: current?.name.orEmpty(),
                        subtitle = queueLabel.orEmpty(),
                        state = playbackState,
                        positionMs = positionMs,
                        durationMs = media?.durationMs ?: 0L,
                        onOpen = { openTab(Routes.PLAYER) },
                        onToggle = viewModel.controller::toggle,
                        onNext = viewModel.controller::next,
                    )
                }
                NavigationBar(containerColor = WaveCueColors.Surface) {
                    NavigationBarItem(
                        selected = route == Routes.LIBRARY,
                        onClick = { openTab(Routes.LIBRARY) },
                        icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                        label = { Text("Library") },
                        modifier = Modifier.testTag(TestTags.NavLibrary),
                    )
                    NavigationBarItem(
                        selected = route == Routes.PLAYLISTS || route == Routes.PLAYLIST_DETAIL,
                        onClick = { openTab(Routes.PLAYLISTS) },
                        icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                        label = { Text("Playlists") },
                        modifier = Modifier.testTag(TestTags.NavPlaylists),
                    )
                    NavigationBarItem(
                        selected = route == Routes.PLAYER,
                        onClick = { openTab(Routes.PLAYER) },
                        icon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
                        label = { Text("Player") },
                        modifier = Modifier.testTag(TestTags.NavPlayer),
                    )
                    NavigationBarItem(
                        selected = route == Routes.SETTINGS,
                        onClick = { openTab(Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        modifier = Modifier.testTag(TestTags.NavSettings),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen()
            }
            composable(Routes.PLAYLISTS) {
                PlaylistsScreen(
                    onOpenPlaylist = { id -> navController.navigate(Routes.playlistDetail(id)) }
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen(onOpenLibrary = { openTab(Routes.LIBRARY) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(
                route = Routes.PLAYLIST_DETAIL,
                arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
            ) {
                PlaylistDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun NowPlayingBar(
    title: String,
    subtitle: String,
    state: PlaybackState,
    positionMs: Long,
    durationMs: Long,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = WaveCueColors.SurfaceHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column {
            val progress = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = WaveCueColors.Primary,
                trackColor = WaveCueColors.Outline,
            )
            Row(
                Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = WaveCueColors.Primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = WaveCueColors.TextSecondary,
                            maxLines = 0,
                        )
                    }
                }
                IconButton(onClick = onToggle, modifier = Modifier.testTag(TestTags.NowPlayingToggle)) {
                    Icon(
                        if (state == PlaybackState.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state == PlaybackState.PLAYING) "Pause" else "Play",
                        tint = WaveCueColors.TextPrimary,
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.testTag(TestTags.NowPlayingNext)) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = WaveCueColors.TextSecondary,
                    )
                }
            }
        }
    }
}
