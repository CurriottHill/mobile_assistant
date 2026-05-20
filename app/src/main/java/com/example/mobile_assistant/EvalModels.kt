package com.example.mobile_assistant

/**
 * Immutable records produced by an eval run. Everything here is plain data so it can be
 * serialized to JSON / the append-only eval database and unit-tested off-device.
 *
 * See [EvalRecorder] for how these are collected and [EvalJudge] for how the verdict is filled.
 */
internal const val EVAL_SCHEMA_VERSION = 2

/** A single tool invocation, mirrored from the agent's existing [ToolCallSummary]. */
internal data class EvalToolCall(
    val stepIndex: Int,
    val name: String,
    val status: String,       // "success" | "failure" (from ToolCallSummary.status)
    val arguments: String,    // complete raw JSON arguments passed to the tool
    val argsSummary: String,  // first ~200 chars of the raw arguments
    val details: String       // ToolCallSummary.details
)

/** Ordered eval timeline, preserving reasoning and tool calls exactly as they occur. */
internal data class EvalTimelineEvent(
    val sequence: Int,
    val stepIndex: Int,
    val type: String,         // "reasoning" | "tool_call"
    val reasoning: String?,
    val toolCall: EvalToolCall?
)

/** One iteration of the agent step loop. */
internal data class EvalStepRecord(
    val stepIndex: Int,
    val thinking: String?,
    /** Relative path under evals/, only set when this step had a failing tool call. */
    val errorScreenshotFile: String?,
    val toolCalls: List<EvalToolCall>
)

internal data class EvalTranscriptEntry(
    val title: String,
    val bodyMarkdown: String
)

/** A moment where the agent did something ineffective or got an error, per the judge. */
internal data class RecoveryEpisode(
    val step: Int,
    val source: String,          // "error_code" | "ineffective"
    val whatWentWrong: String,
    val detected: Boolean,       // did the agent notice and try to correct
    val stepsToRecover: Int,     // -1 == never recovered
    val outcome: String
)

internal data class EvalVerdict(
    val completed: Boolean,
    val confidence: Double,
    val rationale: String,
    val qualityScore: Double,             // Haiku's comparative 0-10 score for this run
    val recoveryEpisodes: List<RecoveryEpisode>,
    val recoveryRate: Double,            // authoritative, from EvalScoring over the episodes
    val source: String,                  // "judge" | "manual_override"
    val manualCompletedOverride: Boolean?,
    val manualRecoveryOverride: Double?
)

internal data class EvalRunRecord(
    val runId: String,
    val timestampMillis: Long,
    val timestampIso: String,
    val taskId: String,
    val goalText: String,
    val successCriteria: String,
    val agentModel: String,
    val judgeModel: String,
    val agentInvoked: Boolean,
    val stepCount: Int,
    val stopReason: String,
    val taskCompleteFired: Boolean,
    val hardLoopDetected: Boolean,
    val maxConsecutiveSameToolCount: Int,
    val userStopped: Boolean,
    val userIntervened: Boolean,
    val interventionReasons: List<String>,
    val finalScreenshotFile: String?,
    val steps: List<EvalStepRecord>,
    val toolCalls: List<EvalToolCall>,
    val timeline: List<EvalTimelineEvent>,
    val transcript: List<EvalTranscriptEntry>,
    val verdict: EvalVerdict?
)

/** Per-model rollup shown on the aggregate panel and used for model comparison. */
internal data class ModelAggregate(
    val agentModel: String,
    val runCount: Int,
    val completionRate: Double,
    val avgSteps: Double,
    val avgToolCalls: Double,
    val avgRecoveryRate: Double,
    val userStoppedCount: Int,
    val userIntervenedCount: Int
)
