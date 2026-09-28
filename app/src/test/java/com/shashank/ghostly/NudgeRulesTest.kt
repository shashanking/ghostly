package com.shashank.ghostly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeRulesTest {

    private val today = 20_000L
    private val hour = 60L * 60 * 1000
    private val base = today * 24 * hour

    private fun input(
        minute: Int,
        streak: StreakState = StreakState(),
        mood: Mood = Mood.CONTENT,
        hunger: Float = 80f,
        energy: Float = 80f,
        sleeping: Boolean = false,
        // Looked after an hour ago, unless a test says otherwise — so "he misses you" stays out of it.
        lastCareAt: Long = base + minute * 60_000L - hour,
        appVisible: Boolean = false,
    ) = NudgeRules.Input(
        now = base + minute * 60_000L,
        today = today,
        minuteOfDay = minute,
        streak = streak,
        name = "Tultul",
        mood = mood,
        hunger = hunger,
        energy = energy,
        sleeping = sleeping,
        lastCareAt = lastCareAt,
        appVisible = appVisible,
    )

    private val atRisk = StreakState(current = 12, longest = 12, lastDay = today - 1)

    @Test
    fun nothingDuringQuietHours() {
        val (n, _) = NudgeRules.decide(input(23 * 60 + 10, atRisk, hunger = 5f), NudgeRules.Log())
        assertNull(n)
        val (m, _) = NudgeRules.decide(input(7 * 60, atRisk, hunger = 5f), NudgeRules.Log())
        assertNull(m)
    }

    @Test
    fun nothingWhileTheAppIsOpen() {
        val (n, _) = NudgeRules.decide(input(20 * 60, atRisk, appVisible = true), NudgeRules.Log())
        assertNull(n)
    }

    @Test
    fun eveningReminderOnceADay() {
        val (n, log) = NudgeRules.decide(input(19 * 60, atRisk), NudgeRules.Log())
        assertEquals(NudgeRules.Kind.STREAK_REMINDER, n?.kind)
        assertTrue(n!!.title.contains("12") || n.title.contains("13"))
        val (again, _) = NudgeRules.decide(input(20 * 60 + 30, atRisk), log)
        assertNull(again)
    }

    @Test
    fun lastCallBreaksThroughTheCap() {
        val full = NudgeRules.Log(day = today, sentToday = NudgeRules.DAILY_CAP, lastSentAt = base + 21 * hour + 20 * 60_000L)
        val (n, _) = NudgeRules.decide(input(21 * 60 + 40, atRisk), full)
        assertEquals(NudgeRules.Kind.STREAK_LAST_CALL, n?.kind)
    }

    @Test
    fun noStreakNudgeOnceTodayCounts() {
        val done = atRisk.copy(lastDay = today)
        val (n, _) = NudgeRules.decide(input(21 * 60 + 45, done), NudgeRules.Log())
        assertNull(n)
    }

    @Test
    fun day2PromptForANewStreak() {
        val one = StreakState(current = 1, longest = 1, lastDay = today - 1)
        val (n, _) = NudgeRules.decide(input(18 * 60 + 5, one), NudgeRules.Log())
        assertEquals(NudgeRules.Kind.STREAK_START, n?.kind)
    }

    @Test
    fun freezeAndLossAreAnnouncedInTheMorning() {
        val frozen = StreakState(current = 9, longest = 9, lastDay = today - 2, freezes = 1, freezeWeek = StreakRules.weekOf(today))
        assertEquals(NudgeRules.Kind.STREAK_FROZEN, NudgeRules.decide(input(9 * 60 + 10, frozen), NudgeRules.Log()).first?.kind)

        val lost = StreakState(current = 9, longest = 15, lastDay = today - 3)
        val (n, log) = NudgeRules.decide(input(9 * 60 + 10, lost), NudgeRules.Log())
        assertEquals(NudgeRules.Kind.STREAK_LOST, n?.kind)
        assertTrue(n!!.text.contains("15"))
        // Said once, not every morning after.
        val (again, _) = NudgeRules.decide(input(9 * 60 + 10, lost), log.copy(day = today - 1))
        assertTrue(again?.kind != NudgeRules.Kind.STREAK_LOST)
    }

    @Test
    fun aMoodIsMentionedWhenItStartsAndNotAgainWhileItLasts() {
        val (n, log) = NudgeRules.decide(input(12 * 60, hunger = 10f), NudgeRules.Log())
        assertEquals(NudgeRules.Kind.HUNGRY, n?.kind)
        val (again, log2) = NudgeRules.decide(input(14 * 60, hunger = 10f), log)
        assertNull(again)
        // Fed, then hungry again later: said again, after the cooldown.
        val (_, fed) = NudgeRules.decide(input(15 * 60, hunger = 90f), log2)
        val (back, _) = NudgeRules.decide(input(17 * 60, hunger = 10f), fed)
        assertEquals(NudgeRules.Kind.HUNGRY, back?.kind)
    }

    @Test
    fun angerOutranksHunger() {
        val (n, _) = NudgeRules.decide(input(12 * 60, mood = Mood.ANGRY, hunger = 10f), NudgeRules.Log())
        assertEquals(NudgeRules.Kind.ANGRY, n?.kind)
        assertEquals("shop", n!!.kind.tab)
    }

    @Test
    fun sleepingHeIsNeitherHungryNorTired() {
        val (n, _) = NudgeRules.decide(input(12 * 60, hunger = 10f, energy = 5f, sleeping = true), NudgeRules.Log())
        assertNull(n)
    }

    @Test
    fun missingYouStepsUpAndResets() {
        val away = input(12 * 60, lastCareAt = base + 12 * hour - 9 * hour)
        val (n, log) = NudgeRules.decide(away, NudgeRules.Log())
        assertEquals(NudgeRules.Kind.MISSING_HOURS, n?.kind)
        val (same, _) = NudgeRules.decide(away.copy(now = away.now + 2 * hour), log)
        assertNull(same)
        val dayAway = input(12 * 60, lastCareAt = base + 12 * hour - 25 * hour)
        assertEquals(NudgeRules.Kind.MISSING_DAY, NudgeRules.decide(dayAway, log.copy(lastSentAt = 0L)).first?.kind)
    }

    @Test
    fun capAndGapHoldBack() {
        val (_, log) = NudgeRules.decide(input(12 * 60, hunger = 10f), NudgeRules.Log())
        // Something new comes up twenty minutes later: too soon.
        val (tooSoon, _) = NudgeRules.decide(input(12 * 60 + 20, hunger = 10f, energy = 5f), log)
        assertNull(tooSoon)
        val (later, _) = NudgeRules.decide(input(13 * 60 + 30, hunger = 10f, energy = 5f), log)
        assertEquals(NudgeRules.Kind.TIRED, later?.kind)
    }

    @Test
    fun switchesAreHonoured() {
        val off = input(19 * 60, atRisk, hunger = 10f).copy(streakOn = false, moodOn = false, missingOn = false)
        assertNull(NudgeRules.decide(off, NudgeRules.Log()).first)
    }

    @Test
    fun logRoundTrips() {
        val log = NudgeRules.Log(
            day = today, sentToday = 2, lastSentAt = 5L, saidToday = setOf("STREAK_REMINDER"),
            moods = setOf("HUNGRY"), moodAt = mapOf("HUNGRY" to 9L), lostFor = 3L, missingTier = 2,
        )
        assertEquals(log, NudgeRules.Log.parse(log.toJson()))
        assertEquals(NudgeRules.Log(), NudgeRules.Log.parse("not json"))
    }

    @Test
    fun everyKindHasWords() {
        NudgeRules.Kind.entries.forEach { kind ->
            val n = NudgeRules.render(kind, "Tultul", 12, 20, today)
            assertTrue(kind.name, n.title.isNotBlank() && n.text.isNotBlank())
        }
    }
}
