package com.example.mobile_assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalRecorderJsonTest {

    private fun sampleRecord(verdict: EvalVerdict?) = EvalRunRecord(
        runId = "run_42",
        timestampMillis = 42L,
        timestampIso = "2026-05-17T00:00:00Z",
        taskId = "task_a",
        goalText = "do the thing",
        successCriteria = "thing is done",
        agentModel = "claude-haiku-4-5-20251001",
        judgeModel = "claude-haiku-4-5-20251001",
        agentInvoked = true,
        stepCount = 2,
        stopReason = "task_complete",
        taskCompleteFired = true,
        hardLoopDetected = false,
        maxConsecutiveSameToolCount = 1,
        userStopped = false,
        userIntervened = true,
        interventionReasons = listOf("ask_user"),
        finalScreenshotFile = "shots/run_42_final.jpg",
        steps = listOf(
            EvalStepRecord(
                stepIndex = 0,
                thinking = "first",
                errorScreenshotFile = null,
                toolCalls = listOf(EvalToolCall(0, "open_app", "success", "{\"name\":\"x\"}", "{\"name\":\"x\"}", "name=x"))
            ),
            EvalStepRecord(
                stepIndex = 1,
                thinking = null,
                errorScreenshotFile = "shots/run_42_error_step01.jpg",
                toolCalls = listOf(EvalToolCall(1, "tap_node", "failure", "{}", "{}", "target=Button"))
            )
        ),
        toolCalls = listOf(
            EvalToolCall(0, "open_app", "success", "{\"name\":\"x\"}", "{\"name\":\"x\"}", "name=x"),
            EvalToolCall(1, "tap_node", "failure", "{}", "{}", "target=Button")
        ),
        timeline = listOf(
            EvalTimelineEvent(0, 0, "reasoning", "first", null),
            EvalTimelineEvent(1, 0, "tool_call", null, EvalToolCall(0, "open_app", "success", "{\"name\":\"x\"}", "{\"name\":\"x\"}", "name=x")),
            EvalTimelineEvent(2, 1, "tool_call", null, EvalToolCall(1, "tap_node", "failure", "{}", "{}", "target=Button"))
        ),
        transcript = listOf(EvalTranscriptEntry("Goal", "do the thing")),
        verdict = verdict
    )

    @Test
    fun roundTrip_withVerdictAndNulls() {
        val verdict = EvalVerdict(
            completed = true,
            confidence = 0.85,
            rationale = "looks done",
            qualityScore = 8.5,
            recoveryEpisodes = listOf(
                RecoveryEpisode(1, "error_code", "tap failed", true, 1, "recovered")
            ),
            recoveryRate = 1.0,
            source = "manual_override",
            manualCompletedOverride = true,
            manualRecoveryOverride = null
        )
        val original = sampleRecord(verdict)

        val json = EvalRecorder.toJson(original)
        val restored = EvalRecorder.fromJson(JSONObject(json.toString()))

        assertEquals(EVAL_SCHEMA_VERSION, json.getInt("schema_version"))
        assertEquals(original.runId, restored.runId)
        assertEquals(original.interventionReasons, restored.interventionReasons)
        assertEquals(original.finalScreenshotFile, restored.finalScreenshotFile)
        assertEquals(original.steps.size, restored.steps.size)
        assertNull(restored.steps[0].errorScreenshotFile)
        assertEquals("shots/run_42_error_step01.jpg", restored.steps[1].errorScreenshotFile)
        assertNull(restored.steps[1].thinking)
        assertEquals(original.toolCalls, restored.toolCalls)
        assertEquals(original.timeline, restored.timeline)
        assertEquals(original.transcript, restored.transcript)
        assertEquals(8.5, restored.verdict?.qualityScore ?: -1.0, 1e-9)
        assertEquals(true, restored.verdict?.manualCompletedOverride)
        assertNull(restored.verdict?.manualRecoveryOverride)
        assertEquals("error_code", restored.verdict?.recoveryEpisodes?.first()?.source)
    }

    @Test
    fun roundTrip_withoutVerdict() {
        val restored = EvalRecorder.fromJson(JSONObject(EvalRecorder.toJson(sampleRecord(null)).toString()))
        assertNull(restored.verdict)
        assertTrue(restored.userIntervened)
    }
}
