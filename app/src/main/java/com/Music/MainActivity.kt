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
import androidx.compose.runtime.*
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

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collectLatest {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

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
                viewModel            = viewModel,
                onNavigateToPlayer   = { navController.navigate(Screen.Player.route) },
                onNavigateToPlaylist = { id -> navController.navigate(Screen.PlaylistDetail.route(id)) }
            )
        }

        composable(
            route = Screen.Player.route,
            enterTransition = {
                // full off-screen start + instant opacity = zero bleed-through from library
                slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness    = Spring.StiffnessMediumLow
                    )
                ) { it } + fadeIn(tween(0))
            },
            exitTransition    = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(0)) },
            popExitTransition = {
                slideOutVertically(
                    animationSpec = tween(240, easing = FastOutLinearInEasing)
                ) { it } + fadeOut(tween(180))
            }
        ) {
            PlayerScreen(
                viewModel          = viewModel,
                onNavigateBack     = { navController.popBackStack() },
                onNavigateToLyrics = { navController.navigate(Screen.Lyrics.route) }
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
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route     = Screen.PlaylistDetail.route,
            arguments = listOf(
                navArgument(Screen.PlaylistDetail.ARG) { type = NavType.LongType }
            ),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness    = Spring.StiffnessMediumLow
                    )
                ) { it } + fadeIn(tween(0))
            },
            exitTransition     = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition  = {
                slideOutHorizontally(
                    animationSpec = tween(220, easing = FastOutLinearInEasing)
                ) { it } + fadeOut(tween(160))
            }
        ) { back ->
            val playlistId = back.arguments?.getLong(Screen.PlaylistDetail.ARG)
                ?: return@composable
            PlaylistDetailScreen(
                playlistId         = playlistId,
                viewModel          = viewModel,
                onBack             = { navController.popBackStack() },
                onNavigateToPlayer = { navController.navigate(Screen.Player.route) }
            )
        }
    }
}