package com.pixelforge.app.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pixelforge.app.MainActivity
import com.pixelforge.app.R
import com.pixelforge.app.core.constants.AppConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts an OS notification when a new PixelForge release is detected.
 * Clicking the notification opens the app and surfaces the update dialog directly.
 */
@Singleton
class AppUpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile
    private var lastNotifiedTag: String? = null

    fun showUpdateNotification(info: AppUpdateInfo, force: Boolean = false) {
        if (!force && lastNotifiedTag == info.tagName) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val channel = NotificationChannel(
            AppConstants.UPDATE_NOTIFICATION_CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when a new PixelForge release is available on GitHub"
        }
        manager.createNotificationChannel(channel)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppConstants.EXTRA_SHOW_UPDATE_DIALOG, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            AppConstants.UPDATE_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, AppConstants.UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("PixelForge ${info.tagName} available")
            .setContentText("Tap to view release notes and download the update")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching {
            manager.notify(AppConstants.UPDATE_NOTIFICATION_ID, notification)
            lastNotifiedTag = info.tagName
        }
    }
}
