package com.shashank.ghostly

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Everything that talks to the server, on one background thread, on a schedule that never blocks
 * the pet.
 *
 * - **Content** refreshes once a day (or when asked): new pack version → download → the brain is
 *   rebuilt on its next tick. This is how "he did something new today" happens without an update.
 * - **State** is pushed when something changed, and pulled when the server's copy is newer.
 * - **Events** queue locally and drain in batches, so a flaky connection loses nothing.
 */
object ContentSync {

    private const val TAG = "GhostlySync"
    private const val CONTENT_REFRESH_MS = 24L * 60 * 60 * 1000
    private const val MAX_QUEUED_EVENTS = 200

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ghostly-sync").apply { isDaemon = true } }

    fun schedule(context: Context, forceContent: Boolean = false) {
        val app = context.applicationContext
        executor.execute { runCatching { sync(app, forceContent) }.onFailure { Log.w(TAG, "sync failed: ${it.message}") } }
    }

    fun recordEvent(context: Context, type: String, meta: JSONObject? = null) {
        val app = context.applicationContext
        executor.execute {
            val queue = pendingEvents(app)
            if (queue.length() >= MAX_QUEUED_EVENTS) queue.remove(0)
            queue.put(JSONObject().put("type", type).put("at", System.currentTimeMillis()).put("meta", meta ?: JSONObject()))
            Prefs.savePendingEvents(app, queue.toString())
        }
    }

    private fun sync(context: Context, forceContent: Boolean) {
        refreshContent(context, forceContent)
        if (!GhostlyApi.hasSession(context)) return
        drainEvents(context)
        // His stats are a slope, not an event: pushing them every ten minutes woke the radio for a
        // round trip whether or not anything had happened. The stats carry the moment they were
        // last written, so if that has not moved, the server already has this.
        val statsAt = Prefs.statsUpdatedAt(context)
        if (statsAt == Prefs.lastSyncedStatsAt(context)) return
        if (GhostlyApi.syncState(context)) Prefs.saveLastSyncedStatsAt(context, statsAt)
    }

    private fun refreshContent(context: Context, force: Boolean) {
        val due = System.currentTimeMillis() - Prefs.contentSyncedAt(context) > CONTENT_REFRESH_MS
        if (!force && !due) return
        val remote = GhostlyApi.manifestVersion(context)
        if (remote > Prefs.contentVersion(context)) {
            val v = GhostlyApi.downloadPack(context)
            Log.i(TAG, "content pack updated to v$v")
        }
        runCatching { Prefs.saveDaily(context, GhostlyApi.fetchDaily(context).toString()) }
        Prefs.saveContentSyncedAt(context, System.currentTimeMillis())
    }

    private fun drainEvents(context: Context) {
        val queue = pendingEvents(context)
        if (queue.length() == 0) return
        if (GhostlyApi.postEvents(context, queue)) Prefs.savePendingEvents(context, "[]")
    }

    private fun pendingEvents(context: Context): JSONArray =
        runCatching { JSONArray(Prefs.pendingEvents(context)) }.getOrDefault(JSONArray())

    /** Today's boosts from the editor: reaction id → weight multiplier. Empty when there is no event. */
    fun dailyBoosts(context: Context): Map<String, Float> {
        val daily = runCatching { JSONObject(Prefs.daily(context)) }.getOrNull() ?: return emptyMap()
        val boost = daily.optJSONObject("boost") ?: return emptyMap()
        val out = HashMap<String, Float>()
        for (key in boost.keys()) out[key] = boost.optDouble(key, 1.0).toFloat().coerceIn(0f, 10f)
        return out
    }
}
