package com.pixelforge.app.presentation.navigation

/**
 * Centralized route definitions — no hardcoded strings elsewhere.
 */
sealed class Routes(val route: String) {
    data object Home : Routes("home")
    data object Editor : Routes("editor/{imageUri}") {
        fun createRoute(imageUri: String): String = "editor/${imageUri}"
    }
    data object Processing : Routes("processing/{workId}") {
        fun createRoute(workId: String): String = "processing/${workId}"
    }
    data object Result : Routes("result/{recordId}") {
        fun createRoute(recordId: Long): String = "result/${recordId}"
    }
    data object History : Routes("history")
    data object Settings : Routes("settings")
    data object About : Routes("about")
}
