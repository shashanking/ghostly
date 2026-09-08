package com.shashank.ghostly

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generated content pack: species voices and a large table of reactions, each with the
 * conditions under which it applies.
 *
 * The pack is authored offline (by an agent, at design time) and shipped in assets, so the pet has
 * a personality without the app ever making a network call. Nothing here is required for the app to
 * run — a missing or malformed pack simply means [BehaviourEngine] falls back to drifting.
 */
class BehaviourPack(
    val species: Map<String, SpeciesProfile>,
    val reactions: List<Reaction>,
) {

    data class SpeciesProfile(
        val syllables: List<String>,
        val byMood: Map<String, VocalPattern>,
        val traits: Map<String, Float>,
    )

    data class VocalPattern(val pattern: String, val repeatMin: Int, val repeatMax: Int)

    data class Reaction(
        val id: String,
        val conditions: Conditions,
        val emote: Emote,
        val locomotion: Locomotion,
        /** A mood key into the species block, or a literal prefixed with "!". */
        val vocal: String?,
        val bubble: Bubble,
        val weight: Int,
        val cooldownSec: Int,
    )

    /**
     * Everything omitted means "don't care" — that is what keeps the pack small. Categorical keys
     * arrive as arrays, booleans as scalars.
     */
    data class Conditions(
        val timeOfDay: Set<String>? = null,
        val dayOfWeek: Set<Int>? = null,
        val species: Set<String>? = null,
        val lastEvent: Set<String>? = null,
        val batteryBucket: Set<String>? = null,
        val charging: Boolean? = null,
        val silentMode: Boolean? = null,
        val headphones: Boolean? = null,
        val isWeekend: Boolean? = null,
        val minHunger: Float? = null,
        val maxHunger: Float? = null,
        val minEnergy: Float? = null,
        val maxEnergy: Float? = null,
        val minHappiness: Float? = null,
        val maxHappiness: Float? = null,
        val minAnger: Float? = null,
        val maxAnger: Float? = null,
        val minStreakDays: Int? = null,
        val maxStreakDays: Int? = null,
        val sinceInteractionMin: Pair<Int?, Int?>? = null,
        val unlocksToday: Pair<Int?, Int?>? = null,
    ) {
        fun matches(c: PetContext): Boolean {
            timeOfDay?.let { if (c.timeOfDay.id !in it) return false }
            dayOfWeek?.let { if (c.dayOfWeek !in it) return false }
            species?.let { if (c.species.id !in it) return false }
            batteryBucket?.let { if (c.batteryBucket.id !in it) return false }
            lastEvent?.let { if ((c.lastEvent?.id ?: "none") !in it) return false }
            charging?.let { if (c.charging != it) return false }
            silentMode?.let { if (c.silentMode != it) return false }
            headphones?.let { if (c.headphones != it) return false }
            isWeekend?.let { if (c.isWeekend != it) return false }
            minHunger?.let { if (c.hunger < it) return false }
            maxHunger?.let { if (c.hunger > it) return false }
            minEnergy?.let { if (c.energy < it) return false }
            maxEnergy?.let { if (c.energy > it) return false }
            minHappiness?.let { if (c.happiness < it) return false }
            maxHappiness?.let { if (c.happiness > it) return false }
            minAnger?.let { if (c.anger < it) return false }
            maxAnger?.let { if (c.anger > it) return false }
            minStreakDays?.let { if (c.streakDays < it) return false }
            maxStreakDays?.let { if (c.streakDays > it) return false }
            sinceInteractionMin?.let { (lo, hi) ->
                if (lo != null && c.minutesSinceInteraction < lo) return false
                if (hi != null && c.minutesSinceInteraction > hi) return false
            }
            unlocksToday?.let { (lo, hi) ->
                if (lo != null && c.unlocksToday < lo) return false
                if (hi != null && c.unlocksToday > hi) return false
            }
            return true
        }
    }

    companion object {
        const val ASSET_NAME = "behaviour-pack.json"

        /**
         * Whichever pack is newer wins, downloaded or bundled, with a tie going to the downloaded
         * one. The bundled pack is the floor the app can never fall below — and an app update that
         * ships new content has to be able to raise that floor. Taking the downloaded pack on sight
         * meant anyone who had ever synced was stuck on whatever the server last sent, however old,
         * and never saw content added in a release.
         */
        fun load(context: Context, assetName: String = ASSET_NAME): BehaviourPack? {
            val bundled = runCatching {
                val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
                JSONObject(json)
            }.getOrNull()
            val downloaded = runCatching {
                java.io.File(context.filesDir, assetName).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
            }.getOrNull()

            val bundledVersion = bundled?.optInt("version", 0) ?: -1
            val downloadedVersion = downloaded?.optInt("version", 0) ?: -1
            val preferred = if (downloadedVersion >= bundledVersion) downloaded else bundled
            val fallback = if (preferred === downloaded) bundled else downloaded

            preferred?.let { json ->
                runCatching { parse(json) }.getOrNull()?.takeIf { it.reactions.isNotEmpty() }?.let { return it }
            }
            return fallback?.let { json -> runCatching { parse(json) }.getOrNull() }
        }

        /** The bundled pack never changes while the process lives, so it is read exactly once. */
        @Volatile
        private var bundledVersion = -1

        /**
         * Version of whichever pack [load] would return — the newer of the two.
         *
         * This is asked for on every behaviour tick, so it must be nearly free. It used to open and
         * fully JSON-parse a quarter-megabyte of content twice each time just to read one integer,
         * which came to thousands of parses a day. The bundled side is now read once per process
         * and the downloaded side comes from the version [GhostlyApi.downloadPack] already records.
         */
        fun loadedVersion(context: Context): Int {
            if (bundledVersion < 0) {
                bundledVersion = runCatching {
                    JSONObject(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
                        .optInt("version", 0)
                }.getOrDefault(0)
            }
            return maxOf(bundledVersion, Prefs.contentVersion(context))
        }

        fun parse(root: JSONObject): BehaviourPack {
            val species = mutableMapOf<String, SpeciesProfile>()
            root.optJSONObject("species")?.let { block ->
                for (key in block.keys()) {
                    block.optJSONObject(key)?.let { species[key] = parseSpecies(it) }
                }
            }

            val reactions = mutableListOf<Reaction>()
            val array = root.optJSONArray("reactions") ?: JSONArray()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { parseReaction(it)?.let(reactions::add) }
            }
            return BehaviourPack(species, reactions)
        }

        private fun parseSpecies(o: JSONObject): SpeciesProfile {
            // The pack nests these under "vocal"; accept them at the top level too, so a
            // hand-written pack doesn't have to.
            val vocal = o.optJSONObject("vocal")
            val syllables = (vocal?.optJSONArray("syllables") ?: o.optJSONArray("syllables"))
                .orEmpty().orEmpty()

            val moodSource = vocal?.optJSONObject("byMood") ?: o.optJSONObject("byMood")
            val moods = mutableMapOf<String, VocalPattern>()
            moodSource?.let { block ->
                for (key in block.keys()) {
                    val m = block.optJSONObject(key) ?: continue
                    val repeat = m.optJSONArray("repeat")
                    moods[key] = VocalPattern(
                        pattern = m.optString("pattern", ""),
                        repeatMin = repeat?.optInt(0, 1) ?: 1,
                        repeatMax = repeat?.optInt(1, 1) ?: 1
                    )
                }
            }

            val traits = mutableMapOf<String, Float>()
            o.optJSONObject("traits")?.let { block ->
                for (key in block.keys()) traits[key] = block.optDouble(key, 0.0).toFloat()
            }
            return SpeciesProfile(syllables, moods, traits)
        }

        private fun parseReaction(o: JSONObject): Reaction? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val emote = Emote.fromId(o.optString("emote")) ?: return null
            val locomotion = Locomotion.fromId(o.optString("locomotion")) ?: Locomotion.DRIFT
            return Reaction(
                id = id,
                conditions = parseConditions(o.optJSONObject("when")),
                emote = emote,
                locomotion = locomotion,
                vocal = o.optString("vocal").takeIf { it.isNotBlank() },
                bubble = Bubble.fromToken(o.optString("bubble")),
                weight = o.optInt("weight", 1).coerceIn(1, 100),
                cooldownSec = o.optInt("cooldownSec", 0).coerceAtLeast(0)
            )
        }

        private fun parseConditions(o: JSONObject?): Conditions {
            if (o == null) return Conditions()
            fun strings(key: String): Set<String>? {
                val array = o.optJSONArray(key).orEmpty()
                if (array != null) return array.map { it.lowercase() }.toSet()
                return o.optString(key).takeIf { it.isNotBlank() }?.let { setOf(it.lowercase()) }
            }

            fun bool(key: String): Boolean? = if (o.has(key) && !o.isNull(key)) o.optBoolean(key) else null
            fun float(key: String): Float? =
                if (o.has(key) && !o.isNull(key)) o.optDouble(key).toFloat() else null

            fun int(key: String): Int? = if (o.has(key) && !o.isNull(key)) o.optInt(key) else null

            fun range(key: String): Pair<Int?, Int?>? = o.optJSONArray(key)?.let {
                val lo = if (it.isNull(0)) null else it.optInt(0)
                val hi = if (it.length() < 2 || it.isNull(1)) null else it.optInt(1)
                lo to hi
            }
            val since = range("sinceInteractionMin")

            val days = o.optJSONArray("dayOfWeek")?.let { array ->
                (0 until array.length()).map { array.optInt(it) }.toSet()
            }

            // Case matters nowhere except the enums, which are matched case-insensitively above.
            return Conditions(
                // Stored as the canonical camelCase ids so matching against PetContext is exact.
                timeOfDay = strings("timeOfDay")?.let { set ->
                    TimeOfDay.entries.filter { it.id.lowercase() in set }.map { it.id }.toSet()
                },
                dayOfWeek = days,
                species = strings("species"),
                lastEvent = strings("lastEvent"),
                batteryBucket = strings("batteryBucket"),
                charging = bool("charging"),
                silentMode = bool("silentMode"),
                headphones = bool("headphones"),
                isWeekend = bool("isWeekend"),
                minHunger = float("minHunger"), maxHunger = float("maxHunger"),
                minEnergy = float("minEnergy"), maxEnergy = float("maxEnergy"),
                minHappiness = float("minHappiness"), maxHappiness = float("maxHappiness"),
                minAnger = float("minAnger"), maxAnger = float("maxAnger"),
                minStreakDays = int("minStreakDays"), maxStreakDays = int("maxStreakDays"),
                sinceInteractionMin = since,
                unlocksToday = range("unlocksToday")
            )
        }

        private fun JSONArray?.orEmpty(): List<String>? {
            if (this == null) return null
            return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
        }
    }
}
