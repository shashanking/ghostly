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

    /** Make sure the server knows this pet; returns its server id. */
    fun ensurePet(context: Context): String? {
        Prefs.petServerId(context)?.let { return it }
        if (!hasSession(context)) return null
        val stats = PetStats.snapshot(context)
        val body = JSONObject()
            .put("slot", 1)
            .put("species", Prefs.species(context).id)
            .put("name", Prefs.name(context) ?: JSONObject.NULL)
            .put("state", stateJson(context, stats))
        val res = call(context, "POST", "/pets", body)
        val id = res.optString("id").takeIf { it.isNotBlank() } ?: return null
        Prefs.savePetServerId(context, id)
        return id
    }

    /**
     * Push local stats. If the server's copy is newer it wins and is written back locally — that is
     * how a second device, or a reinstall, picks the pet up where he actually is.
     */
    fun syncState(context: Context): Boolean {
        val petId = ensurePet(context) ?: return false
        val stats = PetStats.snapshot(context)
        val res = call(context, "PUT", "/pets/$petId/state", stateJson(context, stats))
        val state = res.optJSONObject("state") ?: return false
        if (!res.optBoolean("accepted", true)) {
            Prefs.saveStats(
                context,
                state.optDouble("hunger", stats.hunger.toDouble()).toFloat(),
                state.optDouble("energy", stats.energy.toDouble()).toFloat(),
                state.optDouble("happiness", stats.happiness.toDouble()).toFloat(),
                state.optBoolean("sleeping", stats.sleeping),
                state.optLong("updatedAt", System.currentTimeMillis())
            )
        }
        return true
    }

    fun postEvents(context: Context, events: JSONArray): Boolean {
        if (events.length() == 0) return true
        val petId = ensurePet(context) ?: return false
        val res = call(context, "POST", "/pets/$petId/events", events)
        return res.has("inserted")
    }

    private fun stateJson(context: Context, s: PetStats.Snapshot) = JSONObject()
        .put("hunger", s.hunger)
        .put("energy", s.energy)
        .put("happiness", s.happiness)
        .put("anger", Prefs.anger(context))
        .put("sleeping", s.sleeping)
        .put("sleepStartedAt", Prefs.sleepStartedAt(context))
        .put("updatedAt", Prefs.statsUpdatedAt(context))

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
