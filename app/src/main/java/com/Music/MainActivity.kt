package com.Music

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.Music.ui.theme.MuseTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MuseTheme { MusicApp() }
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
        navController = navController,
        startDestination = Screen.Library.route
    ) {
        composable(
            route = Screen.Library.route,
            enterTransition = { fadeIn(tween(300)) },
            // Stay visible underneath player slide-up
            exitTransition = { ExitTransition.None }
        ) {
            LibraryScreen(
                viewModel = viewModel,
                onNavigateToPlayer = { navController.navigate(Screen.Player.route) }
            )
        }
        composable(
            route = Screen.Player.route,
            enterTransition = {
                slideInVertically(tween(420, easing = EaseOut)) { it }
            },
            exitTransition = {
                slideOutVertically(tween(350, easing = EaseIn)) { it }
            },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = {
                slideOutVertically(tween(350, easing = EaseIn)) { it }
            }
        ) {
            PlayerScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}