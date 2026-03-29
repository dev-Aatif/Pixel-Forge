package com.pixelforge.app.presentation.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.pixelforge.app.presentation.screen.editor.EditorScreen
import com.pixelforge.app.presentation.screen.history.HistoryScreen
import com.pixelforge.app.presentation.screen.home.HomeScreen
import com.pixelforge.app.presentation.screen.processing.ProcessingScreen
import com.pixelforge.app.presentation.screen.result.ResultScreen
import com.pixelforge.app.presentation.screen.settings.SettingsScreen

private const val ANIMATION_DURATION = 300

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.Home.route,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(ANIMATION_DURATION)
            ) + fadeIn(tween(ANIMATION_DURATION))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(ANIMATION_DURATION)
            ) + fadeOut(tween(ANIMATION_DURATION))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(ANIMATION_DURATION)
            ) + fadeIn(tween(ANIMATION_DURATION))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(ANIMATION_DURATION)
            ) + fadeOut(tween(ANIMATION_DURATION))
        }
    ) {
        // Home
        composable(Routes.Home.route) {
            HomeScreen(
                onImageSelected = { uri ->
                    val encoded = Uri.encode(uri.toString())
                    navController.navigate(Routes.Editor.createRoute(encoded))
                },
                onRecentImageSelected = { recordId ->
                    navController.navigate(Routes.Result.createRoute(recordId))
                },
                onNavigateToHistory = {
                    navController.navigate(Routes.History.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.Settings.route)
                }
            )
        }

        // Editor / Preview
        composable(
            route = Routes.Editor.route,
            arguments = listOf(navArgument("imageUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("imageUri") ?: ""
            val imageUri = Uri.decode(encodedUri)
            EditorScreen(
                imageUri = imageUri,
                onStartProcessing = { workId ->
                    navController.navigate(Routes.Processing.createRoute(workId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Processing
        composable(
            route = Routes.Processing.route,
            arguments = listOf(navArgument("workId") { type = NavType.StringType })
        ) { backStackEntry ->
            val workId = backStackEntry.arguments?.getString("workId") ?: ""
            ProcessingScreen(
                workId = workId,
                onComplete = { recordId ->
                    navController.navigate(Routes.Result.createRoute(recordId)) {
                        // Clear processing and editor from back stack
                        popUpTo(Routes.Home.route)
                    }
                },
                onCancel = {
                    navController.popBackStack(Routes.Home.route, inclusive = false)
                }
            )
        }

        // Result
        composable(
            route = Routes.Result.route,
            arguments = listOf(navArgument("recordId") { type = NavType.LongType })
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getLong("recordId") ?: 0L
            ResultScreen(
                recordId = recordId,
                onBack = { navController.popBackStack() },
                onHome = {
                    navController.popBackStack(Routes.Home.route, inclusive = false)
                }
            )
        }

        // History
        composable(Routes.History.route) {
            HistoryScreen(
                onImageSelected = { recordId ->
                    navController.navigate(Routes.Result.createRoute(recordId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Settings
        composable(Routes.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
