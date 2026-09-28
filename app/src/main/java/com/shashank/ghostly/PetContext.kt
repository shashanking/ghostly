package com.shashank.ghostly

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import java.util.Calendar

/** Coarse buckets rather than raw numbers — the pack matches on these, and they change rarely. */
enum class TimeOfDay(val id: String) {
    LATE_NIGHT("lateNight"), DAWN("dawn"), MORNING("morning"),
    AFTERNOON("afternoon"), EVENING("evening"), NIGHT("night");

    companion object {
        fun of(hour: Int): TimeOfDay = when (hour) {
            in 0..4 -> LATE_NIGHT
            in 5..6 -> DAWN
            in 7..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..20 -> EVENING
            else -> NIGHT
        }
    }
}

enum class BatteryBucket(val id: String) {
    CRITICAL("critical"), LOW("low"), MID("mid"), HIGH("high"), FULL("full");

    companion object {
        fun of(percent: Int): BatteryBucket = when {
            percent <= 10 -> CRITICAL
            percent <= 25 -> LOW
            percent <= 60 -> MID
            percent <= 90 -> HIGH
            else -> FULL
        }
    }
}

/** The last thing that happened to him, as far as the pack is concerned. */
enum class PetEvent(val id: String) {
    FED("fed"), PETTED("petted"), PLAYED("played"),
    IGNORED("ignored"), SPOOKED("spooked"), NAPPED("napped")
}

/**
 * The half of [PetContext] that is about the phone rather than about a pet.
 *
 * It is its own type because reading it is not free and the answer is the same for everyone in the
 * room. Battery goes through `registerReceiver` and headphones through `AudioManager.getDevices`,
 * which walks every output device; doing that once per pet per behaviour tick means five of each
 * every fifteen seconds, for five identical answers. Read it once, pass it to each pet.
 *
 * Everything here is a coarse bucket or a boolean, so it stays true for the whole of one tick —
 * there is no accuracy to lose by sharing it.
 */
data class World(
    val timeOfDay: TimeOfDay,
    val dayOfWeek: Int,
    val isWeekend: Boolean,
    val batteryBucket: BatteryBucket,
    val charging: Boolean,
    val silentMode: Boolean,
    val headphones: Boolean,
    val unlocksToday: Int,
) {
    companion object {
        fun read(context: Context): World {
            val calendar = Calendar.getInstance()
            val day = calendar.get(Calendar.DAY_OF_WEEK)
            val battery = readBatteryPercent(context)
            val audio = context.getSystemService(AudioManager::class.java)

            return World(
                timeOfDay = TimeOfDay.of(calendar.get(Calendar.HOUR_OF_DAY)),
                dayOfWeek = day,
                isWeekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY,
                batteryBucket = BatteryBucket.of(battery.first),
                charging = battery.second,
                silentMode = audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL,
                headphones = audio?.let { isHeadsetOn(it) } ?: false,
                unlocksToday = Prefs.unlocksToday(context),
            )
        }

        /** Percent 0..100 and whether it is charging. A sticky broadcast, so this costs nothing. */
        private fun readBatteryPercent(context: Context): Pair<Int, Boolean> {
            val intent = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull() ?: return 100 to false

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return percent to charging
        }

        private fun isHeadsetOn(audio: AudioManager): Boolean =
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            }
    }
}

/**
 * Everything the brain is allowed to know about the world.
 *
 * Deliberately narrow: no foreground app, no screen contents, no typing detection. Those would need
 * UsageStats or an AccessibilityService, which are heavy permissions, hostile to a sideloaded
 * install, and would wreck the app's "collects nothing" position for the sake of a slightly
 * cleverer wiggle.
 *
 * It stays flat rather than holding a [World] because the pack matches on these names directly —
 * see [BehaviourPack.Conditions] — and one pet's context is meant to be readable as a single row.
 * Whose row it is, is [slot]: an engine and a context that belong to different pets would otherwise
 * look identical, and the mismatch would only show up as a pet reacting to somebody else's hunger.
 */
data class PetContext(
    val species: Species,
    val timeOfDay: TimeOfDay,
    val dayOfWeek: Int,
    val isWeekend: Boolean,
    val batteryBucket: BatteryBucket,
    val charging: Boolean,
    val silentMode: Boolean,
    val headphones: Boolean,
    val hunger: Float,
    val energy: Float,
    val happiness: Float,
    val anger: Float,
    val sleeping: Boolean,
    val streakDays: Int,
    val unlocksToday: Int,
    val minutesSinceInteraction: Int,
    val lastEvent: PetEvent?,
    val slot: Int = PetStore.PRIMARY_SLOT,
) {
    companion object {

        /**
         * One pet's view of an already-read [World].
         *
         * Only the per-pet readings happen here — his species, his stats, his anger. The streak is
         * the account's, not his, but it is cheap and the pack matches on it, so it is read
         * alongside them rather than earning a place in [World].
         *
         * [lastEvent] and [lastInteractionAt] stay per-pet arguments: "he was fed eight minutes
         * ago" is about one pet, and sharing it would have petting one of them quieten all five.
         */
        fun of(
            context: Context,
            world: World,
            lastEvent: PetEvent?,
            lastInteractionAt: Long,
            slot: Int = PetStore.PRIMARY_SLOT,
        ): PetContext {
            val stats = PetStats.snapshot(context, slot = slot)

            val sinceMinutes = if (lastInteractionAt <= 0L) {
                Int.MAX_VALUE
            } else {
                ((System.currentTimeMillis() - lastInteractionAt) / 60_000L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            }

            return PetContext(
                species = Prefs.species(context, slot),
                timeOfDay = world.timeOfDay,
                dayOfWeek = world.dayOfWeek,
                isWeekend = world.isWeekend,
                batteryBucket = world.batteryBucket,
                charging = world.charging,
                silentMode = world.silentMode,
                headphones = world.headphones,
                hunger = stats.hunger,
                energy = stats.energy,
                happiness = stats.happiness,
                anger = Prefs.anger(context, slot),
                sleeping = stats.sleeping,
                streakDays = Streak.current(context),
                unlocksToday = world.unlocksToday,
                minutesSinceInteraction = sinceMinutes,
                lastEvent = lastEvent,
                slot = slot,
            )
        }

        /** For one pet on his own: reads the world and builds his context in one go. Correct but
         *  wasteful for a roster — read [World.read] once and use the overload above instead. */
        fun of(
            context: Context,
            lastEvent: PetEvent?,
            lastInteractionAt: Long,
            slot: Int = PetStore.PRIMARY_SLOT,
        ): PetContext = of(context, World.read(context), lastEvent, lastInteractionAt, slot)
    }
}
