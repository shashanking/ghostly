package com.shashank.ghostly

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The thin wire to the Ghostly backend. Plain HttpURLConnection — the app has no HTTP library and
 * does not need one for a handful of small JSON calls.
 *
 * Every call here is synchronous and must run off the main thread; [ContentSync] owns the thread.
 * Nothing in the app *depends* on any of this succeeding: the pet works offline, the server is for
 * continuity across devices, the token economy, and fresh content.
 */
object GhostlyApi {

    const val BASE_URL = "https://skf.npf.mybluehost.me/ghostly/v1"
    private const val TIMEOUT_MS = 10_000

    class ApiException(val status: Int, message: String) : Exception(message)

    // ---- auth ---------------------------------------------------------------------------------

    /** Trade a Google ID token for a session. Stores the session on success. */
    fun authGoogle(context: Context, idToken: String): Boolean {
        val body = JSONObject()
            .put("idToken", idToken)
            .put("deviceId", deviceId(context))
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        val res = call(context, "POST", "/auth/google", body, auth = false)
        val token = res.optString("sessionToken").takeIf { it.isNotBlank() } ?: return false
        val user = res.optJSONObject("user")
        Prefs.saveSession(context, token, user?.optString("id"))
        return true
    }

    fun hasSession(context: Context) = !Prefs.sessionToken(context).isNullOrBlank()

    fun deleteAccount(context: Context): Boolean {
        val res = call(context, "DELETE", "/account")
        val ok = res.optBoolean("deleted", false)
        if (ok) Prefs.clearSession(context)
        return ok
    }

    // ---- pet ----------------------------------------------------------------------------------

    /**
     * One pet as the server has him — what [listPets] hands back.
     *
     * This is not a [Pet]: it carries his stats, which a [Pet] deliberately does not, because here
     * they arrive in the same response and there is nothing yet to write them into.
     */
    data class RemotePet(
        val id: String,
        val slot: Int,
        val species: Species,
        val name: String?,
        /** 0 for a slot that never expires — the primary's, and anything created before leases. */
        val leaseExpiresAt: Long,
        val stats: PetStats.Snapshot,
        val anger: Float,
        val sleepStartedAt: Long,
        val updatedAt: Long,
    )

    /**
     * Make sure the server knows this pet; returns its server id.
     *
     * The id is cached per slot ([Prefs.petServerId]), so each pet is created once and every later
     * call for him is a cache hit. Defaulting [pet] to the primary keeps every existing caller —
     * all of which mean "the only pet there is" — doing exactly what it did.
     */
    fun ensurePet(context: Context, pet: Pet = PetStore.primary(context)): String? {
        Prefs.petServerId(context, pet.slot)?.let { return it }
        if (!hasSession(context)) return null
        val body = JSONObject()
            .put("slot", pet.slot)
            .put("species", pet.species.id)
            .put("name", pet.name ?: JSONObject.NULL)
            .put("leaseExpiresAt", leaseJson(context, pet))
            .put("state", stateJson(context, pet, PetStats.snapshot(context, slot = pet.slot)))
        val res = call(context, "POST", "/pets", body)
        val id = res.optString("id").takeIf { it.isNotBlank() } ?: return null
        Prefs.savePetServerId(context, id, pet.slot)
        return id
    }

    /**
     * Push one pet's stats. If the server's copy is newer it wins and is written back locally —
     * that is how a second device, or a reinstall, picks the pet up where he actually is.
     */
    fun syncState(context: Context, slot: Int = PetStore.PRIMARY_SLOT): Boolean {
        // read, not get: a pet whose lease lapsed mid-sync still has stats worth pushing, and
        // losing them because a clock ran over would be exactly the deletion leases avoid.
        val pet = PetStore.read(context, slot)
        val petId = ensurePet(context, pet) ?: return false
        // Neutral personality on purpose: this is the raw catch-up, the same one this call has
        // always pushed. Which species drains how fast is [Emotions]' layer, above this one.
        val stats = PetStats.snapshot(context, slot = slot)
        val res = call(context, "PUT", "/pets/$petId/state", stateJson(context, pet, stats))
        val state = res.optJSONObject("state") ?: return false
        if (!res.optBoolean("accepted", true)) {
            Prefs.saveStats(
                context,
                state.optDouble("hunger", stats.hunger.toDouble()).toFloat(),
                state.optDouble("energy", stats.energy.toDouble()).toFloat(),
                state.optDouble("happiness", stats.happiness.toDouble()).toFloat(),
                state.optBoolean("sleeping", stats.sleeping),
                state.optLong("updatedAt", System.currentTimeMillis()),
                slot,
            )
            // Anger was sent on every push and thrown away on every reply, so a pet who was wound
            // up on another device arrived home calm. It lives outside [Prefs.saveStats] because
            // it decays on its own schedule — see [Emotions] — so it is written on its own.
            Prefs.saveAnger(context, state.optDouble("anger", Prefs.anger(context, slot).toDouble()).toFloat(), slot)
        }
        return true
    }

    fun postEvents(context: Context, events: JSONArray, slot: Int = PetStore.PRIMARY_SLOT): Boolean {
        if (events.length() == 0) return true
        val petId = ensurePet(context, PetStore.read(context, slot)) ?: return false
        val res = call(context, "POST", "/pets/$petId/events", events)
        return res.has("inserted")
    }

    /**
     * Every pet the account has on the server, lapsed leases included — the missing half of
     * "restore onto a new device", which has no path today.
     *
     * Nothing calls this yet. It is here so the wire side is settled while the shapes are fresh;
     * the decision about what a restore does with a lapsed lease belongs with the UI that shows it.
     */
    fun listPets(context: Context): List<RemotePet> {
        if (!hasSession(context)) return emptyList()
        val arr = JSONArray(raw(context, "GET", "/pets"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val st = o.optJSONObject("state") ?: JSONObject()
            val slot = o.optInt("slot", PetStore.PRIMARY_SLOT)
            RemotePet(
                id = id,
                slot = slot,
                species = Species.fromId(o.optString("species")),
                // optString hands back "" for a JSON null, which is the same thing here: unnamed.
                name = o.optString("name").takeIf { it.isNotBlank() },
                leaseExpiresAt = o.optLong("leaseExpiresAt", 0L),
                stats = PetStats.Snapshot(
                    hunger = st.optDouble("hunger", 0.0).toFloat(),
                    energy = st.optDouble("energy", 0.0).toFloat(),
                    happiness = st.optDouble("happiness", 0.0).toFloat(),
                    sleeping = st.optBoolean("sleeping", false),
                    slot = slot,
                ),
                anger = st.optDouble("anger", 0.0).toFloat(),
                sleepStartedAt = st.optLong("sleepStartedAt", 0L),
                updatedAt = st.optLong("updatedAt", 0L),
            )
        }
    }

    /** A lease as the wire wants it: millis, or null for the primary, who does not expire. */
    private fun leaseJson(context: Context, pet: Pet): Any {
        val until = PetStore.expiresAt(context, pet.slot)
        return if (until == Long.MAX_VALUE || until <= 0L) JSONObject.NULL else until
    }

    /**
     * Species and name ride along with the state because this is the only call made after the pet
     * exists. Without them a restyle — a species swap, a rename — never reaches the server at all,
     * and a restored device brings back whoever he was on the day the row was first written.
     */
    private fun stateJson(context: Context, pet: Pet, s: PetStats.Snapshot) = JSONObject()
        .put("species", pet.species.id)
        .put("name", pet.name ?: JSONObject.NULL)
        .put("hunger", s.hunger)
        .put("energy", s.energy)
        .put("happiness", s.happiness)
        .put("anger", Prefs.anger(context, pet.slot))
        .put("sleeping", s.sleeping)
        .put("sleepStartedAt", Prefs.sleepStartedAt(context, pet.slot))
        .put("updatedAt", Prefs.statsUpdatedAt(context, pet.slot))

    // ---- content ------------------------------------------------------------------------------

    /** Latest pack version the server has. */
    fun manifestVersion(context: Context): Int =
        call(context, "GET", "/content/manifest", auth = false).optInt("version", 0)

    /** Download the active pack to app storage; [BehaviourPack.load] prefers it over the bundled one. */
    fun downloadPack(context: Context): Int {
        val text = raw(context, "GET", "/content/pack", auth = false)
        val version = JSONObject(text).optInt("version", 0)
        if (version <= 0) throw ApiException(500, "pack without version")
        val target = File(context.filesDir, BehaviourPack.ASSET_NAME)
        val tmp = File(context.filesDir, "${BehaviourPack.ASSET_NAME}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) throw ApiException(500, "could not replace pack")
        Prefs.saveContentVersion(context, version)
        return version
    }

    /** Today's editor-set event, if any: `{ "day": ..., "title": ..., "boost": { id: multiplier } }`. */
    fun fetchDaily(context: Context): JSONObject = call(context, "GET", "/content/daily", auth = false)

    // ---- plumbing -----------------------------------------------------------------------------

    private fun call(context: Context, method: String, path: String, body: Any? = null, auth: Boolean = true): JSONObject {
        val text = raw(context, method, path, body, auth)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun raw(context: Context, method: String, path: String, body: Any? = null, auth: Boolean = true): String {
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Ghostly/${appVersion(context)} Android/${Build.VERSION.SDK_INT}")
            if (auth) {
                val token = Prefs.sessionToken(context) ?: throw ApiException(401, "no session")
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status == 401 && auth) Prefs.clearSession(context) // a dead session is not worth retrying
            if (status !in 200..299) throw ApiException(status, "HTTP $status $path: ${text.take(160)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** Stable per-install id; no permission needed and it never leaves this app's scope. */
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}
