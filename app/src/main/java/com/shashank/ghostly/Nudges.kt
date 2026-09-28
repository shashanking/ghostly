package com.shashank.ghostly

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * Everything he tells you while you are not looking: that your streak is about to go, that he is
 * hungry, cross, worn out or down, and that he misses you.
 *
 * A quiet alarm looks in every half hour — inexact and non-waking, so it costs nothing while the
 * phone sleeps and gets its turn the next time the phone is picked up anyway, which is exactly
 * when a nudge is worth seeing. [NudgeRules] decides whether anything is worth saying; this object
 * only gathers what it needs to know and posts the result.
 *
 * Each kind has its own channel, so any of them can be turned off in the system's settings as well
 * as from the app's own switches.
 */
object Nudges {

    const val CHANNEL_STREAK = "streak"
    const val CHANNEL_MOOD = "mood"
    const val CHANNEL_MISSING = "missing"

    private const val REQUEST_CODE = 12
    private const val INTERVAL_MS = AlarmManager.INTERVAL_HALF_HOUR

    /** Set by [MainActivity] while it is on screen — there's no point telling you something you
     *  are already looking at. */
    @Volatile
    var appVisible = false

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // Already set: leave it alone, or opening the app every twenty minutes would keep pushing
        // the next look back and it would never come round.
        val existing = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, NudgeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (existing != null) return
        runCatching {
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pendingIntent(context),
            )
        }
    }

    /** After a reboot the alarm is gone but its PendingIntent may look as if it isn't. */
    fun reschedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
        pendingIntent(context).cancel()
        schedule(context)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, NudgeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Look at how he is, and say something if something is worth saying. */
    fun check(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return

        val s = Emotions.snapshot(context)
        val input = NudgeRules.Input(
            now = System.currentTimeMillis(),
            today = Streak.today(),
            minuteOfDay = LocalTime.now().let { it.hour * 60 + it.minute },
            streak = Streak.state(context),
            name = Prefs.displayName(context),
            mood = s.mood,
            hunger = s.body.hunger,
            energy = s.body.energy,
            sleeping = s.body.sleeping,
            lastCareAt = Prefs.lastCareAt(context).takeIf { it > 0L } ?: Prefs.lastOpenedAt(context),
            appVisible = appVisible,
            streakOn = Prefs.notifyStreak(context),
            moodOn = Prefs.notifyMood(context),
            missingOn = Prefs.notifyMissing(context),
        )
        val (nudge, log) = NudgeRules.decide(input, NudgeRules.Log.parse(Prefs.nudgeLog(context)))
        Prefs.saveNudgeLog(context, log.toJson())
        nudge?.let { post(context, manager, it) }
    }

    /** He was looked after: what he was going to say about being alone no longer holds. */
    fun onCare(context: Context) {
        val now = System.currentTimeMillis()
        Prefs.saveLastCareAt(context, now)
        val log = NudgeRules.Log.parse(Prefs.nudgeLog(context))
        Prefs.saveNudgeLog(context, log.copy(missingTier = 0).toJson())
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.cancel(NudgeRules.Channel.STREAK.notificationId)
            manager.cancel(NudgeRules.Channel.MISSING.notificationId)
        }
    }

    /** Whatever he was complaining about, you are here now. */
    fun clearMood(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(NudgeRules.Channel.MOOD.notificationId)
        }
    }

    /** One of every kind, straight away — a debug-build aid for seeing how they all look. */
    fun previewAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val name = Prefs.displayName(context)
        NudgeRules.Kind.entries.forEachIndexed { i, kind ->
            val nudge = NudgeRules.render(kind, name, streak = 12, longest = 20, today = 0L)
            post(context, manager, nudge, idOverride = 100 + i)
        }
    }

    private fun post(context: Context, manager: NotificationManager, nudge: NudgeRules.Nudge, idOverride: Int? = null) {
        ensureChannels(context, manager)
        val open = PendingIntent.getActivity(
            context, 20 + nudge.kind.ordinal,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TAB, nudge.kind.tab),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            manager.notify(
                idOverride ?: nudge.kind.channel.notificationId,
                Notification.Builder(context, nudge.kind.channel.id)
                    .setSmallIcon(R.drawable.ic_ghost)
                    .setContentTitle(nudge.title)
                    .setContentText(nudge.text)
                    .setStyle(Notification.BigTextStyle().bigText(nudge.text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(if (nudge.kind.channel == NudgeRules.Channel.STREAK) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
                    .build(),
            )
        }
    }

    fun ensureChannels(context: Context, manager: NotificationManager? = context.getSystemService(NotificationManager::class.java)) {
        manager ?: return
        NudgeRules.Channel.entries.forEach { channel ->
            if (manager.getNotificationChannel(channel.id) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(channel.id, channel.label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = channel.description
                    },
                )
            }
        }
    }
}

/** Fired by the half-hourly alarm. */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Nudges.check(context) }
    }
}

/**
 * What is worth saying, and when — kept free of Android so it can be tested against a clock.
 *
 * The house rules, so he is a pet and not a pest:
 * - nothing between 23:00 and 08:00;
 * - nothing while the app is open;
 * - at most [DAILY_CAP] a day, at least [MIN_GAP_MS] apart — except the last call for a streak,
 *   which is the one you would be sorry to miss;
 * - each thing is said once: a mood is only mentioned when it starts (and again only once it has
 *   passed and come back, no sooner than [MOOD_COOLDOWN_MS]); a streak reminder once a day; "he
 *   misses you" once per step of an absence.
 */
object NudgeRules {

    const val DAILY_CAP = 4
    const val MIN_GAP_MS = 60L * 60 * 1000
    const val MOOD_COOLDOWN_MS = 4L * 60 * 60 * 1000

    private const val QUIET_FROM = 23 * 60
    private const val QUIET_UNTIL = 8 * 60
    private const val MORNING = 9 * 60
    private const val EVENING = 18 * 60
    private const val LAST_CALL = 21 * 60 + 30

    const val TIRED_BELOW = 15f

    enum class Channel(val id: String, val label: String, val description: String, val notificationId: Int) {
        STREAK(Nudges.CHANNEL_STREAK, "Streak reminders", "Before your streak runs out, and when it's saved or lost.", 20),
        MOOD(Nudges.CHANNEL_MOOD, "How he's feeling", "When he gets hungry, cross, worn out or down.", 21),
        MISSING(Nudges.CHANNEL_MISSING, "Missing you", "When he hasn't seen you in a while.", 22),
    }

    /** In priority order: when two are due at once, the first one here is said. */
    enum class Kind(val channel: Channel, val tab: String = "home") {
        STREAK_LAST_CALL(Channel.STREAK),
        ANGRY(Channel.MOOD, tab = "shop"),
        STREAK_REMINDER(Channel.STREAK),
        STREAK_FROZEN(Channel.STREAK),
        STREAK_LOST(Channel.STREAK),
        STREAK_START(Channel.STREAK),
        HUNGRY(Channel.MOOD),
        TIRED(Channel.MOOD),
        SAD(Channel.MOOD),
        MISSING_HOURS(Channel.MISSING),
        MISSING_DAY(Channel.MISSING),
        MISSING_DAYS(Channel.MISSING),
    }

    data class Nudge(val kind: Kind, val title: String, val text: String)

    data class Input(
        val now: Long,
        val today: Long,
        val minuteOfDay: Int,
        val streak: StreakState,
        val name: String,
        val mood: Mood,
        val hunger: Float,
        val energy: Float,
        val sleeping: Boolean,
        /** When he was last looked after; 0 if never. */
        val lastCareAt: Long,
        val appVisible: Boolean,
        val streakOn: Boolean = true,
        val moodOn: Boolean = true,
        val missingOn: Boolean = true,
    )

    /** What has already been said, so nothing is said twice. */
    data class Log(
        val day: Long = 0L,
        val sentToday: Int = 0,
        val lastSentAt: Long = 0L,
        /** Once-a-day kinds already said today. */
        val saidToday: Set<String> = emptySet(),
        /** Moods already mentioned and still going on. */
        val moods: Set<String> = emptySet(),
        /** When each mood was last mentioned, for the cooldown. */
        val moodAt: Map<String, Long> = emptyMap(),
        /** The last day of the lapsed streak that "it ended" was already said about. */
        val lostFor: Long = 0L,
        /** How far into an absence "he misses you" has got: 0 none, then 1, 2, 3. */
        val missingTier: Int = 0,
    ) {
        fun toJson(): String = JSONObject()
            .put("day", day)
            .put("sentToday", sentToday)
            .put("lastSentAt", lastSentAt)
            .put("saidToday", JSONArray(saidToday.toList()))
            .put("moods", JSONArray(moods.toList()))
            .put("moodAt", JSONObject().apply { moodAt.forEach { (k, v) -> put(k, v) } })
            .put("lostFor", lostFor)
            .put("missingTier", missingTier)
            .toString()

        companion object {
            fun parse(json: String): Log = runCatching {
                val o = JSONObject(json)
                fun strings(key: String): Set<String> {
                    val a = o.optJSONArray(key) ?: return emptySet()
                    return (0 until a.length()).map { a.getString(it) }.toSet()
                }
                val at = o.optJSONObject("moodAt")
                Log(
                    day = o.optLong("day", 0L),
                    sentToday = o.optInt("sentToday", 0),
                    lastSentAt = o.optLong("lastSentAt", 0L),
                    saidToday = strings("saidToday"),
                    moods = strings("moods"),
                    moodAt = at?.keys()?.asSequence()?.associateWith { at.optLong(it) } ?: emptyMap(),
                    lostFor = o.optLong("lostFor", 0L),
                    missingTier = o.optInt("missingTier", 0),
                )
            }.getOrDefault(Log())
        }
    }

    private const val HOUR = 60L * 60 * 1000

    /** Hours away before each step of "he misses you". */
    private val MISSING_STEPS = listOf(8L * HOUR to Kind.MISSING_HOURS, 24L * HOUR to Kind.MISSING_DAY, 72L * HOUR to Kind.MISSING_DAYS)

    /** Decide what (if anything) to say, and what the log looks like afterwards. */
    fun decide(input: Input, before: Log): Pair<Nudge?, Log> {
        // A new day wipes the daily counts.
        var log = if (before.day != input.today) before.copy(day = input.today, sentToday = 0, saidToday = emptySet()) else before

        // Moods that have passed are forgotten, so they can be mentioned again if they come back.
        val moodsNow = currentMoods(input)
        log = log.copy(moods = log.moods intersect moodsNow)

        if (input.appVisible) return null to log
        val m = input.minuteOfDay
        if (m >= QUIET_FROM || m < QUIET_UNTIL) return null to log

        val candidates = candidates(input, log, moodsNow)
        val capped = log.sentToday >= DAILY_CAP || input.now - log.lastSentAt < MIN_GAP_MS
        val kind = candidates.firstOrNull { !capped || it == Kind.STREAK_LAST_CALL } ?: return null to log

        val s = input.streak
        val nudge = render(kind, input.name, s.current, s.longest, input.today)
        log = log.copy(sentToday = log.sentToday + 1, lastSentAt = input.now)
        log = when (kind) {
            Kind.STREAK_LAST_CALL, Kind.STREAK_REMINDER, Kind.STREAK_FROZEN, Kind.STREAK_START ->
                log.copy(saidToday = log.saidToday + kind.name)
            Kind.STREAK_LOST -> log.copy(lostFor = s.lastDay)
            Kind.ANGRY, Kind.HUNGRY, Kind.TIRED, Kind.SAD ->
                log.copy(moods = log.moods + kind.name, moodAt = log.moodAt + (kind.name to input.now))
            Kind.MISSING_HOURS -> log.copy(missingTier = 1)
            Kind.MISSING_DAY -> log.copy(missingTier = 2)
            Kind.MISSING_DAYS -> log.copy(missingTier = 3)
        }
        return nudge to log
    }

    fun currentMoods(input: Input): Set<String> = buildSet {
        if (input.mood == Mood.ANGRY) add(Kind.ANGRY.name)
        if (!input.sleeping && input.hunger <= PetStats.HUNGRY_THRESHOLD) add(Kind.HUNGRY.name)
        if (!input.sleeping && input.energy < TIRED_BELOW) add(Kind.TIRED.name)
        if (input.mood == Mood.SAD && input.hunger > PetStats.HUNGRY_THRESHOLD) add(Kind.SAD.name)
    }

    private fun candidates(input: Input, log: Log, moodsNow: Set<String>): List<Kind> {
        val out = mutableListOf<Kind>()
        val m = input.minuteOfDay
        val s = input.streak
        val status = StreakRules.status(s, input.today)
        fun notSaid(kind: Kind) = kind.name !in log.saidToday

        if (input.streakOn) {
            val alive = status == StreakRules.Status.AT_RISK || status == StreakRules.Status.FROZEN
            if (alive && s.current >= 2 && m >= LAST_CALL && notSaid(Kind.STREAK_LAST_CALL)) out += Kind.STREAK_LAST_CALL
            if (status == StreakRules.Status.AT_RISK && s.current >= 2 && m >= EVENING &&
                notSaid(Kind.STREAK_REMINDER) && notSaid(Kind.STREAK_LAST_CALL)
            ) {
                out += Kind.STREAK_REMINDER
            }
            if (status == StreakRules.Status.FROZEN && m >= MORNING && notSaid(Kind.STREAK_FROZEN)) out += Kind.STREAK_FROZEN
            if (status == StreakRules.Status.NONE && s.current >= 2 && s.lastDay >= input.today - 3 &&
                s.lastDay != log.lostFor && m >= MORNING
            ) {
                out += Kind.STREAK_LOST
            }
            if (status == StreakRules.Status.AT_RISK && s.current == 1 && m >= EVENING && notSaid(Kind.STREAK_START)) {
                out += Kind.STREAK_START
            }
        }

        if (input.moodOn) {
            for (kind in listOf(Kind.ANGRY, Kind.HUNGRY, Kind.TIRED, Kind.SAD)) {
                if (kind.name !in moodsNow || kind.name in log.moods) continue
                val last = log.moodAt[kind.name] ?: 0L
                if (input.now - last < MOOD_COOLDOWN_MS) continue
                out += kind
            }
        }

        if (input.missingOn && input.lastCareAt > 0L) {
            val away = input.now - input.lastCareAt
            // Only the furthest step reached, and only if it hasn't been said yet.
            MISSING_STEPS.withIndex().lastOrNull { away >= it.value.first }?.let { (i, step) ->
                if (log.missingTier < i + 1) out += step.second
            }
        }

        out.sortBy { it.ordinal }
        return out
    }

    /** The words. A couple of ways of saying most things, turned over day by day. */
    fun render(kind: Kind, name: String, streak: Int, longest: Int, today: Long): Nudge {
        fun pick(vararg options: Pair<String, String>): Nudge {
            val (title, text) = options[Math.floorMod(today, options.size.toLong()).toInt()]
            return Nudge(kind, title, text)
        }
        return when (kind) {
            Kind.STREAK_LAST_CALL -> pick(
                "⌛ Your $streak-day streak ends at midnight" to
                    "$name is waiting up for you. Feed, pet or play with him to keep it alive.",
                "⌛ Last call for your $streak-day streak" to
                    "A minute with $name before midnight keeps your streak going.",
            )
            Kind.STREAK_REMINDER -> pick(
                "🔥 $streak days with $name" to "Don't break the streak — say hi to him before midnight.",
                "🔥 Keep your $streak-day streak going" to "$name hasn't seen you today. Feed him or play fetch to keep it alive.",
                "🔥 Day ${streak + 1} is waiting" to "Pop in and look after $name to make it ${streak + 1} days in a row.",
            )
            Kind.STREAK_FROZEN -> Nudge(
                kind, "🧊 $name saved your streak",
                "You missed yesterday, so he used this week's streak freeze. Visit today to keep your $streak days.",
            )
            Kind.STREAK_LOST -> Nudge(
                kind, "💔 Your $streak-day streak ended",
                "No hard feelings — $name is ready to start a new one. Your best is $longest days.",
            )
            Kind.STREAK_START -> Nudge(
                kind, "🔥 One more day makes it a streak",
                "You looked after $name yesterday. Do it again today and your streak begins.",
            )
            Kind.ANGRY -> pick(
                "😤 $name is upset with you" to "He's been left alone too long. A Gift from the Shop will win him back.",
                "😤 $name is sulking" to "He's in a mood with you. A Gift or a treat from the Shop will help.",
            )
            Kind.HUNGRY -> pick(
                "🍙 $name is hungry" to "His tummy is rumbling. Feeding him is always free.",
                "🍙 $name is peckish" to "He keeps looking at you like you're a snack. Time to feed him?",
            )
            Kind.TIRED -> Nudge(
                kind, "😴 $name can barely keep his eyes open",
                "Let him have a nap — it's free, and he'll wake up bouncy.",
            )
            Kind.SAD -> pick(
                "🥺 $name is feeling a little down" to "A quick game of fetch would cheer him right up.",
                "🥺 $name could use some company" to "Pet him for a moment — it always helps.",
            )
            Kind.MISSING_HOURS -> Nudge(kind, "👀 $name is wondering where you went", "He keeps peeking round the corner of your screen.")
            Kind.MISSING_DAY -> Nudge(kind, "🥺 $name misses you", "It's been a whole day. Come and say hi?")
            Kind.MISSING_DAYS -> Nudge(kind, "👻 It's been a few days…", "$name saved you a spot. He'd love to see you.")
        }
    }
}
