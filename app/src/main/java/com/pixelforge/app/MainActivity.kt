package com.pixelforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.rememberNavController
import com.pixelforge.app.presentation.navigation.NavGraph
import com.pixelforge.app.presentation.theme.PixelForgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            PixelForgeTheme {
                val navController = rememberNavController()
                NavGraph(navController = navController)
            }
        }
    }
}
