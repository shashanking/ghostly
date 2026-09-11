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

    /** Which pet a queued event is about. Absent on anything queued before there was more than one
     *  pet to be about — see [drainEvents]. */
    private const val KEY_SLOT = "slot"

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ghostly-sync").apply { isDaemon = true } }

    fun schedule(context: Context, forceContent: Boolean = false) {
        val app = context.applicationContext
        executor.execute { runCatching { sync(app, forceContent) }.onFailure { Log.w(TAG, "sync failed: ${it.message}") } }
    }

    /**
     * Queue one event for [slot]'s pet.
     *
     * The slot is written down with the event rather than looked up when the queue drains: an event
     * belongs to whoever did it at the moment it happened, and a lease can lapse between the two.
     */
    fun recordEvent(context: Context, type: String, meta: JSONObject? = null, slot: Int = PetStore.PRIMARY_SLOT) {
        val app = context.applicationContext
        executor.execute {
            val queue = pendingEvents(app)
            if (queue.length() >= MAX_QUEUED_EVENTS) queue.remove(0)
            queue.put(
                JSONObject()
                    .put("type", type)
                    .put("at", System.currentTimeMillis())
                    .put("meta", meta ?: JSONObject())
                    .put(KEY_SLOT, slot)
            )
            Prefs.savePendingEvents(app, queue.toString())
        }
    }

    private fun sync(context: Context, forceContent: Boolean) {
        refreshContent(context, forceContent)
        if (!GhostlyApi.hasSession(context)) return
        drainEvents(context)
        for (slot in PetStore.occupiedSlots(context)) syncStats(context, slot)
    }

    /**
     * Push one pet's stats, if they have moved since the last push that the server accepted.
     *
     * His stats are a slope, not an event: pushing them every ten minutes woke the radio for a round
     * trip whether or not anything had happened. The stats carry the moment they were last written,
     * so if that has not moved, the server already has this.
     *
     * The watermark is per pet, and has to be: one shared comparison would let whichever pet moved
     * most recently mark every other pet as synced, and a pet nobody had touched in a week would
     * never be pushed again.
     */
    private fun syncStats(context: Context, slot: Int) {
        val statsAt = Prefs.statsUpdatedAt(context, slot)
        if (statsAt == Prefs.lastSyncedStatsAt(context, slot)) return
        if (GhostlyApi.syncState(context, slot)) Prefs.saveLastSyncedStatsAt(context, statsAt, slot)
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

    /**
     * Send the queue, split by pet.
     *
     * Events are posted to `/pets/{id}/events`, so the queue cannot go as one batch once there is
     * more than one pet — it has to be sorted by whose it is first. A batch that fails leaves only
     * its own pet's events behind, in the order they were recorded, so one unreachable pet does not
     * make the others retry forever.
     *
     * An event queued before the slot was written down belongs to the only pet that install had.
     * The field rides along to the server, which ignores it: the URL already says which pet.
     */
    private fun drainEvents(context: Context) {
        val queue = pendingEvents(context)
        if (queue.length() == 0) return
        val bySlot = LinkedHashMap<Int, JSONArray>()
        for (i in 0 until queue.length()) {
            val event = queue.optJSONObject(i) ?: continue
            bySlot.getOrPut(event.optInt(KEY_SLOT, PetStore.PRIMARY_SLOT)) { JSONArray() }.put(event)
        }
        val failed = HashSet<Int>()
        for ((slot, batch) in bySlot) if (!GhostlyApi.postEvents(context, batch, slot)) failed += slot
        if (failed.isEmpty()) {
            Prefs.savePendingEvents(context, "[]")
            return
        }
        val keep = JSONArray()
        for (i in 0 until queue.length()) {
            val event = queue.optJSONObject(i) ?: continue
            if (event.optInt(KEY_SLOT, PetStore.PRIMARY_SLOT) in failed) keep.put(event)
        }
        Prefs.savePendingEvents(context, keep.toString())
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
