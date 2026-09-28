package com.shashank.ghostly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreakRulesTest {

    /** A Monday, so week boundaries are easy to reason about. 2026-09-28 is a Monday. */
    private val monday = java.time.LocalDate.of(2026, 9, 28).toEpochDay()

    private fun care(s: StreakState, day: Long) = StreakRules.onCare(s, day)

    @Test
    fun firstCareStartsAStreak() {
        val r = care(StreakState(), monday)
        assertEquals(StreakRules.Event.STARTED, r.event)
        assertEquals(1, r.state.current)
        assertEquals(1, r.state.longest)
        assertEquals(monday, r.state.lastDay)
    }

    @Test
    fun secondCareTheSameDayChangesNothing() {
        val day1 = care(StreakState(), monday).state
        val again = care(day1, monday)
        assertEquals(StreakRules.Event.ALREADY_COUNTED, again.event)
        assertEquals(day1, again.state)
    }

    @Test
    fun consecutiveDaysExtend() {
        var s = StreakState()
        for (i in 0 until 5) s = care(s, monday + i).state
        assertEquals(5, s.current)
        assertEquals(StreakRules.Status.DONE_TODAY, StreakRules.status(s, monday + 4))
        assertEquals(StreakRules.Status.AT_RISK, StreakRules.status(s, monday + 5))
    }

    @Test
    fun oneMissedDayIsSavedByTheWeeklyFreeze() {
        var s = care(StreakState(), monday).state
        s = care(s, monday + 1).state
        // Missed Wednesday.
        assertEquals(StreakRules.Status.FROZEN, StreakRules.status(s, monday + 3))
        assertEquals(2, StreakRules.effective(s, monday + 3))
        val r = care(s, monday + 3)
        assertEquals(StreakRules.Event.SAVED_BY_FREEZE, r.event)
        assertEquals(3, r.state.current)
        assertEquals(0, r.state.freezes)
    }

    @Test
    fun aSecondMissInTheSameWeekBreaksIt() {
        var s = care(StreakState(), monday).state
        s = care(s, monday + 2).state // freeze used
        assertEquals(0, s.freezes)
        assertEquals(StreakRules.Status.NONE, StreakRules.status(s, monday + 4))
        val r = care(s, monday + 4)
        assertEquals(StreakRules.Event.RESTARTED, r.event)
        assertEquals(1, r.state.current)
        assertEquals(2, r.lost)
        assertEquals(2, r.state.longest)
    }

    @Test
    fun twoMissedDaysBreakItEvenWithAFreeze() {
        val s = care(StreakState(), monday).state
        assertEquals(StreakRules.Status.NONE, StreakRules.status(s, monday + 3))
        assertEquals(0, StreakRules.effective(s, monday + 3))
    }

    @Test
    fun theFreezeComesBackOnMonday() {
        var s = care(StreakState(), monday + 3).state // Thursday
        s = care(s, monday + 5).state // missed Friday, freeze spent on Saturday
        assertEquals(0, s.freezes)
        s = care(s, monday + 6).state // Sunday
        // Missed next Monday: the new week's freeze covers it on Tuesday.
        val r = care(s, monday + 8)
        assertEquals(StreakRules.Event.SAVED_BY_FREEZE, r.event)
        assertEquals(4, r.state.current)
    }

    @Test
    fun longestSurvivesARestart() {
        var s = StreakState()
        for (i in 0 until 10) s = care(s, monday + i).state
        s = care(s, monday + 20).state
        assertEquals(1, s.current)
        assertEquals(10, s.longest)
    }

    @Test
    fun milestonesPayOnce() {
        var s = StreakState()
        val rewards = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until 31) {
            val r = care(s, monday + i)
            s = r.state
            if (r.milestone != null) rewards += r.milestone!! to r.reward!!
        }
        assertEquals(listOf(3 to 5, 7 to 10, 14 to 15, 30 to 30), rewards)
    }

    @Test
    fun nextMilestone() {
        assertEquals(3 to 5, StreakRules.nextMilestone(0))
        assertEquals(7 to 10, StreakRules.nextMilestone(3))
        assertEquals(400 to 50, StreakRules.nextMilestone(365))
        assertNull(StreakRules.rewardFor(4))
    }

    @Test
    fun weeksStartOnMonday() {
        assertEquals(StreakRules.weekOf(monday), StreakRules.weekOf(monday + 6))
        assertEquals(StreakRules.weekOf(monday) + 1, StreakRules.weekOf(monday + 7))
        assertEquals(StreakRules.weekOf(monday) - 1, StreakRules.weekOf(monday - 1))
    }
}
