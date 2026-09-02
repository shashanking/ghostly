package com.shashank.ghostly

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the ghost back after a reboot or an app update, if he was floating before.
 *
 * A reboot is one of the exemptions for starting a foreground service; an app update is not, so
 * that start can be refused — [Recall] handles the refusal instead of letting it crash the process.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Recall.wanted(context)) return

        Recall.bringBack(
            context,
            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                "The app was updated. Tap to send him back out."
            } else {
                "Tap to send him back out."
            }
        )
    }
}
