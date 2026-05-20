package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalAggregateStatsTest {

    private fun verdict(completed: Boolean, recovery: Double, manualCompleted: Boolean? = null) =
        EvalVerdict(
            completed = completed,
            confidence = 0.9,
            rationale = "r",
            qualityScore = if (completed) 9.0 else 3.0,
            recoveryEpisodes = emptyList(),
            recoveryRate = recovery,
            source = if (manualCompleted != null) "manual_override" else "judge",
            manualCompletedOverride = manualCompleted,
            manualRecoveryOverride = null
        )

    private fun record(
        model: String,
        steps: Int,
        tools: Int,
        verdict: EvalVerdict?,
        userStopped: Boolean = false,
        userIntervened: Boolean = false
    ) = EvalRunRecord(
        runId = "run_1", timestampMillis = 1L, timestampIso = "iso",
        taskId = "t", goalText = "g", successCriteria = "s",
        agentModel = model, judgeModel = "h", agentInvoked = true,
        stepCount = steps, stopReason = "task_complete", taskCompleteFired = true,
        hardLoopDetected = false, maxConsecutiveSameToolCount = 0,
        userStopped = userStopped, userIntervened = userIntervened, interventionReasons = emptyList(),
        finalScreenshotFile = null,
        steps = emptyList(),
        toolCalls = List(tools) { EvalToolCall(0, "tap", "success", "{}", "{}", "") },
        timeline = emptyList(),
        transcript = emptyList(),
        verdict = verdict
    )

    @Test
    fun aggregatesGroupByModelAndComputeRates() {
        val records = listOf(
            record("haiku", steps = 4, tools = 3, verdict = verdict(true, 1.0)),
            record("haiku", steps = 6, tools = 5, verdict = verdict(false, 0.5)),
            record("sonnet", steps = 2, tools = 1, verdict = verdict(true, 0.8))
        )
        val agg = EvalScoring.aggregateByModel(records).associateBy { it.agentModel }

        val haiku = agg.getValue("haiku")
        assertEquals(2, haiku.runCount)
        assertEquals(0.5, haiku.completionRate, 1e-9)   // 1 of 2 completed
        assertEquals(5.0, haiku.avgSteps, 1e-9)         // (4+6)/2
        assertEquals(4.0, haiku.avgToolCalls, 1e-9)     // (3+5)/2
        assertEquals(0.75, haiku.avgRecoveryRate, 1e-9) // (1.0+0.5)/2

        val sonnet = agg.getValue("sonnet")
        assertEquals(1, sonnet.runCount)
        assertEquals(1.0, sonnet.completionRate, 1e-9)
    }

    @Test
    fun manualOverrideWinsForCompletion() {
        val records = listOf(
            record("haiku", 1, 1, verdict(completed = false, recovery = 0.0, manualCompleted = true))
        )
        assertEquals(1.0, EvalScoring.aggregateByModel(records).first().completionRate, 1e-9)
    }

    @Test
    fun runsWithoutVerdictDoNotDivideByZero() {
        val records = listOf(record("haiku", 3, 2, verdict = null))
        val a = EvalScoring.aggregateByModel(records).first()
        assertEquals(0.0, a.completionRate, 0.0)
        assertEquals(3.0, a.avgSteps, 0.0)
        assertEquals(0.0, a.avgRecoveryRate, 0.0)
    }
}
