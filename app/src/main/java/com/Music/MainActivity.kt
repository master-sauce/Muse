package com.Music

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.Music.ui.theme.MuseTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
        setContent {
            MuseTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr
                ) {
                    MusicApp()
                }
            }
        }
    }
}

@Composable
fun MusicApp() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val context = LocalContext.current

    // The morphing player overlay lives above the nav graph so it can slide
    // up from the mini bar regardless of which screen is showing.
    var playerExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collectLatest {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }
    // Errors from the app-scoped download engine (survives Activity death).
    LaunchedEffect(Unit) {
        viewModel.downloadErrorEvents.collectLatest {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }
    // Share intents emitted by the download engine (links file / library export).
    LaunchedEffect(Unit) {
        viewModel.downloadShareIntents.collect { intent ->
            context.startActivity(intent)
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController    = navController,
            startDestination = Screen.Library.route
        ) {

            composable(
                route              = Screen.Library.route,
                enterTransition    = { fadeIn(tween(200)) },
                exitTransition     = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition  = { fadeOut(tween(120)) }
            ) {
                LibraryScreen(
                    viewModel                 = viewModel,
                    onNavigateToPlayer        = { playerExpanded = true },
                    onNavigateToPlaylist      = { id -> navController.navigate(Screen.PlaylistDetail.route(id)) },
                    onNavigateToYouTubeSearch = { navController.navigate(Screen.YouTubeSearch.route) }
                )
            }

            composable(
                route = Screen.Lyrics.route,
                enterTransition = {
                    slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness    = Spring.StiffnessMediumLow
                        )
                    ) { it } + fadeIn(tween(0))
                },
                exitTransition     = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(0)) },
                popExitTransition  = {
                    slideOutVertically(
                        animationSpec = tween(220, easing = FastOutLinearInEasing)
                    ) { it } + fadeOut(tween(160))
                }
            ) {
                LyricsScreen(
                    viewModel      = viewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                        // Re-open the player overlay behind the lyrics sheet so
                        // dismissing lyrics returns to the full player.
                        playerExpanded = true
                    }
                )
            }

            composable(
                route     = Screen.YouTubeSearch.route,
                enterTransition = {
                    slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness    = Spring.StiffnessMediumLow
                        )
                    ) { it } + fadeIn(tween(0))
                },
                exitTransition     = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(0)) },
                popExitTransition  = {
                    slideOutVertically(
                        animationSpec = tween(220, easing = FastOutLinearInEasing)
                    ) { it } + fadeOut(tween(160))
                }
            ) {
                YouTubeSearchScreen(
                    viewModel = viewModel,
                    onBack    = { navController.popBackStack() }
                )
            }

            composable(
                route     = Screen.PlaylistDetail.route,
                arguments = listOf(
                    navArgument(Screen.PlaylistDetail.ARG) { type = NavType.LongType }
                ),
                enterTransition = {
                    scaleIn(
                        initialScale = 0.85f,
                        animationSpec = tween(240, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(240))
                },
                exitTransition     = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition  = {
                    scaleOut(
                        targetScale = 0.85f,
                        animationSpec = tween(220, easing = FastOutLinearInEasing)
                    ) + fadeOut(tween(220))
                }
            ) { back ->
                val playlistId = back.arguments?.getLong(Screen.PlaylistDetail.ARG)
                    ?: return@composable
                PlaylistDetailScreen(
                    playlistId         = playlistId,
                    viewModel          = viewModel,
                    onBack             = { navController.popBackStack() },
                    onNavigateToPlayer = { playerExpanded = true }
                )
            }
        }

        // Morphing player overlay on top of the nav graph. When the user opens
        // lyrics we collapse it first so the lyrics nav screen is visible, and
        // re-expand it when lyrics is dismissed.
        PlayerOverlay(
            viewModel          = viewModel,
            expanded           = playerExpanded,
            onExpand           = { playerExpanded = true },
            onCollapse         = { playerExpanded = false },
            onNavigateToLyrics = {
                playerExpanded = false
                navController.navigate(Screen.Lyrics.route)
            }
        )
    }
}
