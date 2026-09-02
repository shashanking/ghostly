package com.shashank.ghostly

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * A quiet safety net. Phones — Samsung's in particular — will happily kill a long-running
 * foreground service to save battery, and the user is then left with no ghost and no clue why.
 * This checks every quarter of an hour or so and brings him back if he is supposed to be out.
 *
 * The alarm is inexact and non-waking: it never wakes a sleeping device, it just gets its turn the
 * next time the phone is up anyway.
 */
object Watchdog {

    private const val REQUEST_CODE = 11
    private val INTERVAL_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pendingIntent(context)
            )
        }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/** Restarts the overlay if it should be running but isn't. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (GhostOverlayService.isRunning) return
        if (!Recall.wanted(context)) return
        Recall.bringBack(context, "Your phone put him to sleep. Tap to send him back out.")
    }
}
