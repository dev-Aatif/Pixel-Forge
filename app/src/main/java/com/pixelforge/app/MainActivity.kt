package com.pixelforge.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.data.update.AppUpdateEngine
import com.pixelforge.app.data.update.UpdateCheckResult
import com.pixelforge.app.presentation.components.UpdateAvailableDialog
import com.pixelforge.app.presentation.navigation.NavGraph
import com.pixelforge.app.presentation.theme.PixelForgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appUpdateEngine: AppUpdateEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        val launchedFromNotification = handleUpdateIntent(intent)
        if (!launchedFromNotification) {
            lifecycleScope.launch {
                appUpdateEngine.checkIfDueOnLaunch()
            }
        }

        setContent {
            PixelForgeTheme {
                val navController = rememberNavController()
                val updateState by appUpdateEngine.updateState.collectAsStateWithLifecycle()
                val showUpdateDialog by appUpdateEngine.showUpdateDialog.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()

                NavGraph(navController = navController)

                val available = (updateState as? UpdateCheckResult.UpdateAvailable)?.info
                if (showUpdateDialog && available != null) {
                    UpdateAvailableDialog(
                        updateInfo = available,
                        onDismissLater = {
                            scope.launch { appUpdateEngine.dismissUpdateDialog(skipThisVersion = false) }
                        },
                        onSkipVersion = {
                            scope.launch { appUpdateEngine.dismissUpdateDialog(skipThisVersion = true) }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(AppConstants.EXTRA_SHOW_UPDATE_DIALOG, false) == true) {
            intent.removeExtra(AppConstants.EXTRA_SHOW_UPDATE_DIALOG)
            if (appUpdateEngine.updateState.value is UpdateCheckResult.UpdateAvailable) {
                appUpdateEngine.openUpdateDialogIfAvailable()
            } else {
                lifecycleScope.launch { appUpdateEngine.checkForUpdates(manual = true) }
            }
            return true
        }
        return false
    }
}
