package com.shashank.ghostly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Getting the ghost back out after something took him away — a reboot, an app update, or a phone
 * deciding to kill the service to save battery.
 *
 * Android will not always let an app start a foreground service from the background: unless the app
 * is exempt from battery optimisation, a start from a broadcast is refused once the process is
 * gone. When that happens there is nothing clever left to do, so leave a notification the user can
 * tap rather than pretending nothing happened.
 */
object Recall {

    private const val CHANNEL_ID = "ghost_returning"
    private const val NOTIFICATION_ID = 8

    /** Should he be out at all? */
    fun wanted(context: Context): Boolean =
        Prefs.isEnabled(context) && Settings.canDrawOverlays(context)

    /** Try to bring him back; if Android refuses, leave a one-tap way back instead. */
    fun bringBack(context: Context, text: String) {
        if (GhostOverlayService.start(context)) return
        notifyStopped(context, text)
    }

    fun notifyStopped(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Bringing the ghost back",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { setShowBadge(false) }
            )
        }

        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )

        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_ghost)
                    .setContentTitle("Ghostly stopped floating")
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    fun clearNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
