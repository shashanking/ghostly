package com.shashank.ghostly

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Tells you when a newer Ghostly is live on Play, and installs it without sending you out to the
 * store listing.
 *
 * The flexible flow, not the immediate one: he is a pet, not a bank, and blocking the app behind a
 * mandatory update would be rude. The download happens in the background while you carry on, and
 * finishing it is offered — in the app if you are here, as a notification if you have gone.
 *
 * Nothing here works on a sideloaded build: Play answers "no update available" for an app it did
 * not install, which is exactly the right answer.
 */
object AppUpdates {

    private const val CHANNEL_ID = "ghost_updates"
    private const val NOTIFICATION_ID = 4211
    private const val REQUEST_CODE = 8801

    /** Don't ask again for a day once the offer has been declined. */
    private const val REMIND_AFTER_MS = 24 * 60 * 60 * 1000L

    private var listener: InstallStateUpdatedListener? = null

    /**
     * Asks Play whether there is a newer build, and offers it. Safe to call on every resume — it
     * is throttled, and it does nothing at all when the answer is no.
     *
     * [onOffer] is handed a ready-to-run action so the caller can present the offer in its own
     * voice rather than having a dialog thrown at it from here.
     */
    fun check(activity: Activity, onOffer: (start: () -> Unit) -> Unit) {
        val manager = runCatching { AppUpdateManagerFactory.create(activity) }.getOrNull() ?: return
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                // Downloaded while you were away: all that is left is to restart into it.
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    notifyReady(activity)
                    onOffer { manager.completeUpdate() }
                    return@addOnSuccessListener
                }
                if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@addOnSuccessListener
                if (!info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) return@addOnSuccessListener
                if (System.currentTimeMillis() - Prefs.updatePromptedAt(activity) < REMIND_AFTER_MS) {
                    return@addOnSuccessListener
                }
                onOffer {
                    Prefs.saveUpdatePromptedAt(activity, System.currentTimeMillis())
                    if (listener == null) {
                        val l = InstallStateUpdatedListener { state ->
                            if (state.installStatus() == InstallStatus.DOWNLOADED) notifyReady(activity)
                        }
                        listener = l
                        manager.registerListener(l)
                    }
                    runCatching {
                        manager.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, activity, REQUEST_CODE)
                    }
                }
            }
            .addOnFailureListener { /* No Play, no network, sideloaded: simply no update today. */ }
    }

    /** Marks the offer as declined for now, so it is not put in front of you again today. */
    fun snooze(context: Context) {
        Prefs.saveUpdatePromptedAt(context, System.currentTimeMillis())
    }

    /** Drops the install listener — call when the screen offering the update goes away. */
    fun release(context: Context) {
        val l = listener ?: return
        listener = null
        runCatching { AppUpdateManagerFactory.create(context).unregisterListener(l) }
    }

    /** Finishes an update that has already downloaded. */
    fun complete(context: Context) {
        runCatching { AppUpdateManagerFactory.create(context).completeUpdate() }
    }

    /**
     * The download finished. If the app is open the caller offers a restart in place; this is for
     * everyone who has already wandered off, and it survives them coming back later.
     */
    fun notifyReady(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Tells you when a new Ghostly is ready to install." }
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_FINISH_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_ghost)
                    .setContentTitle("A new Ghostly is ready")
                    .setContentText("Tap to finish installing it.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    fun clearNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}
