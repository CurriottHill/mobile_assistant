package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure [StopwatchState] model through the start -> pause -> resume -> reset ->
 * status lifecycle, mirroring how ClockToolService transitions it, so regressions in the
 * accumulate/running-delta math are caught without an Android context.
 */
class StopwatchStateMachineTest {

    private fun start(now: Long, label: String? = null) =
        StopwatchState(label = label, isRunning = true, accumulatedMs = 0L, startedAtEpochMs = now)

    private fun pause(state: StopwatchState, now: Long) =
        state.copy(
            isRunning = false,
            accumulatedMs = state.elapsedMs(now),
            startedAtEpochMs = null
        )

    private fun resume(state: StopwatchState, now: Long) =
        if (state.isRunning) state else state.copy(isRunning = true, startedAtEpochMs = now)

    private fun reset() = StopwatchState()

    @Test
    fun fullLifecycleAccumulatesElapsedCorrectly() {
        // start at t=1000
        var state = start(1_000L, label = "run")
        assertTrue(state.isRunning)
        assertEquals(2_000L, state.elapsedMs(3_000L)) // ran 2s

        // pause at t=3000 -> 2000ms banked, not running
        state = pause(state, 3_000L)
        assertFalse(state.isRunning)
        assertEquals(2_000L, state.accumulatedMs)
        // status while paused is stable regardless of clock advancing
        assertEquals(2_000L, state.elapsedMs(9_999L))

        // resume at t=10000
        state = resume(state, 10_000L)
        assertTrue(state.isRunning)
        assertEquals(2_000L + 5_000L, state.elapsedMs(15_000L)) // banked 2s + 5s running

        // pause again at t=15000 -> 7000ms banked
        state = pause(state, 15_000L)
        assertEquals(7_000L, state.accumulatedMs)

        // reset clears everything
        state = reset()
        assertFalse(state.isRunning)
        assertEquals(0L, state.accumulatedMs)
        assertEquals(0L, state.elapsedMs(99_999L))
    }

    @Test
    fun resumeIsIdempotentWhileRunning() {
        val running = start(1_000L)
        val resumed = resume(running, 5_000L)
        // resuming an already-running stopwatch must not drop accumulated running time
        assertEquals(running.startedAtEpochMs, resumed.startedAtEpochMs)
        assertEquals(4_000L, resumed.elapsedMs(5_000L))
    }

    @Test
    fun roundTripsThroughJson() {
        val state = pause(start(1_000L, label = "lap"), 4_000L)
        val restored = StopwatchState.fromJson(state.toJson())
        assertEquals(state, restored)
        assertEquals(3_000L, restored?.elapsedMs(99_999L))
    }
}
