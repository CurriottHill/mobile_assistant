package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockToolStateSupportTest {
    @Test
    fun selectTrackedTimer_prefersMostRecentActiveTimer() {
        val now = 10_000L
        val selected = ClockToolStateSupport.selectTrackedTimer(
            timers = listOf(
                TrackedTimer(
                    id = "expired",
                    durationSeconds = 60,
                    startedAtEpochMs = 1_000L,
                    expiresAtEpochMs = 2_000L
                ),
                TrackedTimer(
                    id = "older_active",
                    durationSeconds = 60,
                    startedAtEpochMs = 3_000L,
                    expiresAtEpochMs = 12_000L
                ),
                TrackedTimer(
                    id = "newer_active",
                    durationSeconds = 60,
                    startedAtEpochMs = 5_000L,
                    expiresAtEpochMs = 14_000L
                )
            ),
            requestedLabel = null,
            nowEpochMs = now
        )

        assertEquals("newer_active", selected?.id)
    }

    @Test
    fun selectTrackedTimer_matchesLabelIgnoringCase() {
        val selected = ClockToolStateSupport.selectTrackedTimer(
            timers = listOf(
                TrackedTimer(
                    id = "tea",
                    label = "Tea",
                    durationSeconds = 120,
                    startedAtEpochMs = 1_000L,
                    expiresAtEpochMs = 10_000L
                ),
                TrackedTimer(
                    id = "pasta",
                    label = "Pasta",
                    durationSeconds = 600,
                    startedAtEpochMs = 2_000L,
                    expiresAtEpochMs = 15_000L
                )
            ),
            requestedLabel = "tea",
            nowEpochMs = 5_000L
        )

        assertEquals("tea", selected?.id)
    }

    @Test
    fun selectTrackedTimer_returnsNullWhenLabelDoesNotMatch() {
        val selected = ClockToolStateSupport.selectTrackedTimer(
            timers = listOf(
                TrackedTimer(
                    id = "pasta",
                    label = "Pasta",
                    durationSeconds = 600,
                    startedAtEpochMs = 2_000L,
                    expiresAtEpochMs = 15_000L
                )
            ),
            requestedLabel = "eggs",
            nowEpochMs = 5_000L
        )

        assertNull(selected)
    }

    @Test
    fun formatDurationForSpeech_handlesHoursMinutesAndSeconds() {
        val formatted = ClockToolStateSupport.formatDurationForSpeech(
            totalMs = ((2 * 60 * 60) + (5 * 60) + 9) * 1000L
        )

        assertEquals("2 hours 5 minutes 9 seconds", formatted)
    }

    @Test
    fun stopwatchElapsedMs_includesRunningDelta() {
        val state = StopwatchState(
            label = "run",
            isRunning = true,
            accumulatedMs = 15_000L,
            startedAtEpochMs = 20_000L
        )

        assertEquals(20_000L, state.elapsedMs(25_000L))
    }

    @Test
    fun parseWeekdays_handlesCommaSeparatedNames() {
        val days = ClockToolStateSupport.parseWeekdays("monday, wed, friday")

        assertEquals(
            listOf(
                java.util.Calendar.MONDAY,
                java.util.Calendar.WEDNESDAY,
                java.util.Calendar.FRIDAY
            ),
            days
        )
    }
}
