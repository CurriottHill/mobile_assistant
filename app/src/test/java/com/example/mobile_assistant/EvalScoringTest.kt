package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalScoringTest {

    private fun ep(detected: Boolean, steps: Int) =
        RecoveryEpisode(0, "ineffective", "x", detected, steps, "y")

    @Test
    fun episodeScore_boundaries() {
        assertEquals(0.0, EvalScoring.episodeScore(detected = false, stepsToRecover = 0), 0.0)
        assertEquals(0.0, EvalScoring.episodeScore(detected = true, stepsToRecover = -1), 0.0)
        assertEquals(1.0, EvalScoring.episodeScore(detected = true, stepsToRecover = 0), 0.0)
        assertEquals(1.0, EvalScoring.episodeScore(detected = true, stepsToRecover = 1), 0.0)
        assertEquals(0.6, EvalScoring.episodeScore(detected = true, stepsToRecover = 2), 0.0)
        assertEquals(0.0, EvalScoring.episodeScore(detected = true, stepsToRecover = 3), 0.0)
    }

    @Test
    fun recoveryRate_emptyMeansPerfect() {
        assertEquals(1.0, EvalScoring.recoveryRate(emptyList()), 0.0)
    }

    @Test
    fun recoveryRate_averagesEpisodes() {
        val episodes = listOf(
            ep(true, 1),   // 1.0
            ep(true, 2),   // 0.6
            ep(false, 0)   // 0.0
        )
        assertEquals((1.0 + 0.6 + 0.0) / 3.0, EvalScoring.recoveryRate(episodes), 1e-9)
    }

    @Test
    fun recoveryRate_repeatedDudActionIsZero() {
        assertEquals(0.0, EvalScoring.recoveryRate(listOf(ep(true, 4), ep(true, -1))), 0.0)
    }
}
