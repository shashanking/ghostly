package com.shashank.ghostly

import android.content.Context
import java.time.LocalDate

/**
 * Days in a row you have looked after him — Snapchat's streak, for a ghost.
 *
 * Opening the app is not enough. The day only counts once you *do* something with him in it:
 * feed him, play, pet him in his box, put him down for a nap, or give him something from the
 * Shop. That is what makes the number mean something.
 *
 * Days are the phone's own calendar days, rolling over at local midnight — "did you see him
 * today?" is a question about your day, not about UTC.
 *
 * Missing a day is forgiven once a week: a freeze, refilled every Monday, carries the streak over
 * a single empty day. Miss two in a row, or a second day in the same week, and it starts again —
 * but the longest run is always kept, so nothing is ever wiped out entirely.
 *
 * [StreakRules] is the whole of the logic and knows nothing about Android, so it can be tested
 * day by day; [Streak] just loads and saves around it.
 */
object Streak {

    /** Today, as the phone's own calendar sees it. */
    fun today(): Long = LocalDate.now().toEpochDay()

    fun state(context: Context): StreakState = Prefs.streakState(context)

    /**
     * Record that he was looked after just now. Returns what that did to the streak, so the caller
     * can celebrate it — and pays out any milestone reward into the wallet.
     */
    fun recordCare(context: Context): StreakRules.CareResult {
        val result = StreakRules.onCare(state(context), today())
        if (result.event != StreakRules.Event.ALREADY_COUNTED) {
            Prefs.saveStreakState(context, result.state)
            result.reward?.let { Emotions.addTokens(context, it) }
        }
        // Every bit of care, not just the day's first, is what "he misses you" counts from — and
        // it makes today's streak reminders moot. See [Nudges].
        Nudges.onCare(context)
        return result
    }

    /** The streak as it stands right now: zero once it has lapsed, even before anything is done
     *  about it, so the number on screen is never one that can no longer be kept. */
    fun current(context: Context): Int = StreakRules.effective(state(context), today())

    fun status(context: Context): StreakRules.Status = StreakRules.status(state(context), today())
}

/**
 * Everything the streak remembers.
 *
 * [lastDay] is a local epoch day, 0 meaning "never". [freezeWeek] is the week the freeze was last
 * refilled in — see [StreakRules.weekOf].
 */
data class StreakState(
    val current: Int = 0,
    val longest: Int = 0,
    val lastDay: Long = 0L,
    val freezes: Int = StreakRules.FREEZES_PER_WEEK,
    val freezeWeek: Long = 0L,
)

object StreakRules {

    const val FREEZES_PER_WEEK = 1

    enum class Status {
        /** No streak going — never started, or it lapsed. */
        NONE,

        /** Today already counts. */
        DONE_TODAY,

        /** Yesterday counted and today hasn't yet: it ends at midnight unless he's looked after. */
        AT_RISK,

        /** Yesterday was missed, but a freeze is there to cover it — if today counts. */
        FROZEN,
    }

    enum class Event {
        /** The first day of a brand new streak (nothing was going before). */
        STARTED,

        /** One more day on a streak that was alive. */
        EXTENDED,

        /** A missed day was covered by the weekly freeze, and the streak carries on. */
        SAVED_BY_FREEZE,

        /** There was a streak, it lapsed, and this is day one of the next. */
        RESTARTED,

        /** Today had already counted — nothing changed. */
        ALREADY_COUNTED,
    }

    data class CareResult(
        val state: StreakState,
        val event: Event,
        /** The length of the streak that lapsed, for [Event.RESTARTED]; 0 otherwise. */
        val lost: Int = 0,
        /** Set when this day landed on a milestone. */
        val milestone: Int? = null,
        /** Tokens that milestone pays out. */
        val reward: Int? = null,
    )

    /** Days that are worth a fuss, and what each one pays. Past the table, every hundredth day. */
    private val MILESTONES = linkedMapOf(
        3 to 5,
        7 to 10,
        14 to 15,
        30 to 30,
        50 to 40,
        100 to 75,
        150 to 50,
        200 to 50,
        365 to 100,
    )

    fun rewardFor(day: Int): Int? = MILESTONES[day] ?: if (day > 365 && day % 100 == 0) 50 else null

    /** The next milestone after [day], and what it pays — for "3 more days to +10". */
    fun nextMilestone(day: Int): Pair<Int, Int> {
        MILESTONES.forEach { (at, reward) -> if (at > day) return at to reward }
        val next = (day / 100 + 1) * 100
        return next to (rewardFor(next) ?: 50)
    }

    /**
     * Monday-based week number. Epoch day 0 (1 January 1970) was a Thursday, so shifting by three
     * lands every Monday on the start of a new week.
     */
    fun weekOf(day: Long): Long = Math.floorDiv(day + 3, 7L)

    /** The weekly freeze comes back on the first look in a new week. */
    fun refilled(s: StreakState, today: Long): StreakState {
        val week = weekOf(today)
        return if (week != s.freezeWeek) s.copy(freezes = FREEZES_PER_WEEK, freezeWeek = week) else s
    }

    fun status(s: StreakState, today: Long): Status {
        if (s.current <= 0 || s.lastDay <= 0L) return Status.NONE
        val gap = today - s.lastDay
        return when {
            gap <= 0L -> Status.DONE_TODAY
            gap == 1L -> Status.AT_RISK
            gap == 2L && refilled(s, today).freezes > 0 -> Status.FROZEN
            else -> Status.NONE
        }
    }

    fun effective(s: StreakState, today: Long): Int =
        if (status(s, today) == Status.NONE) 0 else s.current

    fun onCare(before: StreakState, today: Long): CareResult {
        val s = refilled(before, today)
        val status = status(s, today)
        if (status == Status.DONE_TODAY) return CareResult(before, Event.ALREADY_COUNTED)

        val (current, freezes, event) = when (status) {
            Status.AT_RISK -> Triple(s.current + 1, s.freezes, Event.EXTENDED)
            Status.FROZEN -> Triple(s.current + 1, s.freezes - 1, Event.SAVED_BY_FREEZE)
            else -> Triple(1, s.freezes, if (s.current > 0) Event.RESTARTED else Event.STARTED)
        }
        val after = s.copy(
            current = current,
            longest = maxOf(s.longest, current),
            lastDay = today,
            freezes = freezes,
        )
        val reward = rewardFor(current)
        return CareResult(
            state = after,
            event = event,
            lost = if (event == Event.RESTARTED) s.current else 0,
            milestone = if (reward != null) current else null,
            reward = reward,
        )
    }
}
