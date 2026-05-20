package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalJudgeParsingTest {

    private val cleanJson = """
        {"completed": true, "confidence": 0.9, "quality_score": 8.25, "rationale": "done",
         "recovery_rate": 0.4,
         "recovery_episodes": [
           {"step": 3, "source": "ineffective", "what_went_wrong": "missed Post",
            "detected": true, "steps_to_recover": 2, "outcome": "fixed"}
         ]}
    """.trimIndent()

    @Test
    fun parsesCleanJson_andRecomputesAuthoritativeRecovery() {
        val v = EvalJudge.parseVerdict(cleanJson)!!
        assertEquals(true, v.completed)
        assertEquals(8.25, v.qualityScore, 1e-9)
        assertEquals(1, v.recoveryEpisodes.size)
        // One episode, recovered in 2 steps -> EvalScoring gives 0.6, overriding judge's 0.4.
        assertEquals(0.6, v.recoveryRate, 1e-9)
        assertEquals("judge", v.source)
        assertTrue(v.rationale.contains("self-reported"))
    }

    @Test
    fun parsesFencedJson() {
        val fenced = "Here you go:\n```json\n$cleanJson\n```\n"
        val v = EvalJudge.parseVerdict(fenced)!!
        assertEquals(true, v.completed)
    }

    @Test
    fun parsesJsonWithLeadingProse() {
        val v = EvalJudge.parseVerdict("Sure! $cleanJson Thanks.")!!
        assertEquals(1, v.recoveryEpisodes.size)
    }

    @Test
    fun noEpisodesMeansPerfectRecovery() {
        val v = EvalJudge.parseVerdict(
            """{"completed": false, "confidence": 0.2, "rationale": "nope", "recovery_episodes": []}"""
        )!!
        assertEquals(1.0, v.recoveryRate, 0.0)
    }

    @Test
    fun malformedReturnsNull() {
        assertNull(EvalJudge.parseVerdict("totally not json"))
        assertNull(EvalJudge.parseVerdict("{ broken "))
    }
}
