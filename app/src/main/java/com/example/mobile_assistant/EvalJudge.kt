package com.example.mobile_assistant

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Asks Haiku (vision) to judge whether the run completed the task and how well the
 * agent recovered from mistakes. Recovery episodes come from BOTH tool error codes
 * and visually-ineffective actions; the deterministic [EvalScoring] formula turns the
 * judge's episodes into the authoritative recovery rate.
 *
 * [parseVerdict] is pure (org.json only) so it is unit-testable; the network call is not.
 */
internal class EvalJudge(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun judge(
        record: EvalRunRecord,
        evalsDir: File,
        previousRunsForTask: List<EvalRunRecord> = emptyList()
    ): EvalVerdict =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildRequest(record, evalsDir, previousRunsForTask)
                val request = Request.Builder()
                    .url(ANTHROPIC_MESSAGES_ENDPOINT)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use sentinel("JUDGE_HTTP_${response.code}: ${bodyStr.take(400)}")
                    }
                    val text = extractAnthropicText(bodyStr)
                    parseVerdict(text) ?: sentinel("JUDGE_PARSE_FAILED: ${text.take(400)}")
                }
            }.getOrElse { sentinel("JUDGE_EXCEPTION: ${it.message?.take(400)}") }
        }

    private fun buildRequest(
        record: EvalRunRecord,
        evalsDir: File,
        previousRunsForTask: List<EvalRunRecord>
    ): JSONObject {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", buildAnalysisText(record, previousRunsForTask)))

        for (shot in collectShots(record)) {
            val b64 = encodeJpeg(File(evalsDir, shot.relativePath)) ?: continue
            content.put(
                JSONObject().put("type", "text").put("text", shot.caption)
            )
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", b64)
                    )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", RESPONSE_INSTRUCTION))

        return JSONObject()
            .put("model", AgentModelConfig.EVAL_HAIKU_MODEL)
            .put("max_tokens", 1500)
            .put("system", SYSTEM_PROMPT)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)
                )
            )
    }

    private data class Shot(val relativePath: String, val caption: String)

    /** On-error shots (chronological) + the final shot, capped at 12. */
    private fun collectShots(record: EvalRunRecord): List<Shot> {
        val errorShots = record.steps
            .filter { it.errorScreenshotFile != null }
            .map { Shot(it.errorScreenshotFile!!, "Screenshot after a FAILED tool at step ${it.stepIndex}:") }
        val finalShot = record.finalScreenshotFile?.let {
            Shot(it, "Final screen state when the run ended:")
        }
        val maxErrors = if (finalShot != null) MAX_SHOTS - 1 else MAX_SHOTS
        val trimmed = if (errorShots.size > maxErrors) errorShots.takeLast(maxErrors) else errorShots
        return trimmed + listOfNotNull(finalShot)
    }

    private fun buildAnalysisText(record: EvalRunRecord, previousRunsForTask: List<EvalRunRecord>): String {
        val timeline = record.timeline
            .ifEmpty { fallbackTimeline(record) }
            .sortedBy { it.sequence }
            .joinToString("\n") { event ->
                when (event.type) {
                    "reasoning" -> {
                        "- #${event.sequence} step ${event.stepIndex} reasoning: ${event.reasoning.orEmpty()}"
                    }
                    "tool_call" -> {
                        val t = event.toolCall
                        if (t == null) {
                            "- #${event.sequence} step ${event.stepIndex} tool_call: (missing)"
                        } else {
                            "- #${event.sequence} step ${t.stepIndex} tool ${t.name} [${t.status}] args=${t.arguments} result=${t.details}".trim()
                        }
                    }
                    else -> "- #${event.sequence} step ${event.stepIndex} ${event.type}: ${event.reasoning.orEmpty()}"
                }
            }
        val transcript = AgentSessionFormatting.buildActionTranscriptMessage(
            record.transcript.map { ActionTranscriptEntry(it.title, it.bodyMarkdown) }
        ) ?: "(no transcript)"
        val previous = previousRunsForTask
            .takeLast(3)
            .joinToString("\n\n") { previousRunSummary(it) }
        return buildString {
            appendLine("GOAL: ${record.goalText}")
            appendLine("SUCCESS CRITERIA: ${record.successCriteria}")
            appendLine("TASK ID: ${record.taskId}")
            appendLine()
            appendLine("STOP REASON: ${record.stopReason}")
            appendLine("agent_invoked=${record.agentInvoked} task_complete_fired=${record.taskCompleteFired}")
            appendLine("hard_loop_detected=${record.hardLoopDetected} max_same_tool_repeats=${record.maxConsecutiveSameToolCount}")
            appendLine("user_stopped=${record.userStopped} user_intervened=${record.userIntervened} reasons=${record.interventionReasons}")
            appendLine()
            appendLine("ORDERED TIMELINE (reasoning and every tool call in execution order; tool status is system-level only):")
            appendLine(timeline.ifBlank { "(none)" })
            appendLine()
            appendLine("LAST THREE SAVED EVALS FOR THIS TASK:")
            appendLine(previous.ifBlank { "(none)" })
            appendLine()
            appendLine(transcript)
        }.trim()
    }

    private fun fallbackTimeline(record: EvalRunRecord): List<EvalTimelineEvent> {
        var seq = 0
        val events = mutableListOf<EvalTimelineEvent>()
        record.steps.sortedBy { it.stepIndex }.forEach { step ->
            step.thinking?.let {
                events += EvalTimelineEvent(seq++, step.stepIndex, "reasoning", it, null)
            }
            step.toolCalls.forEach { tool ->
                events += EvalTimelineEvent(seq++, step.stepIndex, "tool_call", null, tool)
            }
        }
        return events
    }

    private fun previousRunSummary(record: EvalRunRecord): String {
        val verdict = record.verdict
        val timelinePreview = record.timeline
            .ifEmpty { fallbackTimeline(record) }
            .sortedBy { it.sequence }
            .takeLast(12)
            .joinToString("\n") { event ->
                when (event.type) {
                    "reasoning" -> "  #${event.sequence} step ${event.stepIndex} reasoning: ${event.reasoning.orEmpty().take(240)}"
                    "tool_call" -> {
                        val t = event.toolCall
                        if (t == null) "  #${event.sequence} tool: (missing)"
                        else "  #${event.sequence} step ${t.stepIndex} tool ${t.name} [${t.status}] args=${t.arguments.take(240)}"
                    }
                    else -> "  #${event.sequence} ${event.type}"
                }
            }
        return buildString {
            appendLine("run_id=${record.runId} timestamp=${record.timestampIso} model=${record.agentModel}")
            appendLine("stop=${record.stopReason} steps=${record.stepCount} tools=${record.toolCalls.size}")
            appendLine(
                "verdict completed=${verdict?.completed} quality_score=${verdict?.qualityScore} recovery=${verdict?.recoveryRate} rationale=${verdict?.rationale?.take(300)}"
            )
            appendLine("recent timeline:")
            append(timelinePreview.ifBlank { "  (none)" })
        }.trim()
    }

    private fun encodeJpeg(file: File): String? = runCatching {
        if (!file.exists()) return null
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    private fun extractAnthropicText(body: String): String {
        val json = JSONObject(body)
        val contentArr = json.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    private fun sentinel(reason: String) = EvalVerdict(
        completed = false,
        confidence = 0.0,
        rationale = reason,
        recoveryEpisodes = emptyList(),
        qualityScore = 0.0,
        recoveryRate = 1.0,
        source = "judge",
        manualCompletedOverride = null,
        manualRecoveryOverride = null
    )

    companion object {
        private const val ANTHROPIC_MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_SHOTS = 12

        private val SYSTEM_PROMPT = """
            You are a strict evaluator of a phone-controlling AI agent. You are given the
            task goal, success criteria, the ordered tool calls (with their system-level
            status), the ordered reasoning, the last three saved evals for the same task,
            the action transcript, and screenshots taken when a tool FAILED plus the final
            screen.

            Decide:
            1. completed: did the agent actually achieve the success criteria? Judge from
               evidence (final screenshot + transcript), not from the agent's own claims.
            2. quality_score: a 0-10 score for how well THIS run did. Compare against the
               last three saved evals for this task when available, but judge this run on
               its own evidence. 10 means completed efficiently with no meaningful mistakes;
               7-9 means completed with minor inefficiency or recovery; 4-6 means partial
               progress or messy completion; 1-3 means mostly failed; 0 means no useful
               progress or could not run.
            3. recovery_episodes: every point where the agent went wrong. Two kinds:
               (a) "error_code": a tool returned a failure status.
               (b) "ineffective": a tool reported success but did NOT achieve its intent
                   (e.g. tapped the Post button but missed it — the screen did not change
                   as required). Detect these from the transcript + screenshots, not status.
               For each episode set: detected (did the agent notice and try to fix it),
               steps_to_recover (extra steps until it was back on track; -1 if it never
               recovered or kept repeating the same dud action), and a short outcome.
            Repeating the same ineffective action ~3+ times is poor recovery.
            Use the hard/soft loop counters only as a corroborating hint.
        """.trimIndent()

        private val RESPONSE_INSTRUCTION = """
            Return ONLY a JSON object, no prose, no code fences:
            {
              "completed": true|false,
              "confidence": 0.0-1.0,
              "quality_score": 0.0-10.0,
              "rationale": "2-4 sentences citing concrete evidence",
              "recovery_rate": 0.0-1.0,
              "recovery_episodes": [
                {"step": <int>, "source": "error_code"|"ineffective",
                 "what_went_wrong": "...", "detected": true|false,
                 "steps_to_recover": <int, -1 if never>, "outcome": "..."}
              ]
            }
        """.trimIndent()

        /** Pure parser (org.json only) — unit-tested. Returns null on any parse failure
         *  so the caller can fall back to a sentinel + manual entry. */
        fun parseVerdict(rawText: String): EvalVerdict? {
            val jsonStr = extractJson(rawText) ?: return null
            return runCatching {
                val o = JSONObject(jsonStr)
                val epsJson = o.optJSONArray("recovery_episodes") ?: JSONArray()
                val episodes = (0 until epsJson.length())
                    .mapNotNull { epsJson.optJSONObject(it) }
                    .map { e ->
                        RecoveryEpisode(
                            step = e.optInt("step", -1),
                            source = e.optString("source", "ineffective"),
                            whatWentWrong = e.optString("what_went_wrong"),
                            detected = e.optBoolean("detected", false),
                            stepsToRecover = e.optInt("steps_to_recover", -1),
                            outcome = e.optString("outcome")
                        )
                    }
                val judgeReported = o.optDouble("recovery_rate", Double.NaN)
                val authoritative = EvalScoring.recoveryRate(episodes)
                val rationale = buildString {
                    append(o.optString("rationale"))
                    if (!judgeReported.isNaN()) {
                        append(" [judge self-reported recovery=")
                        append(String.format("%.2f", judgeReported))
                        append("]")
                    }
                }
                EvalVerdict(
                    completed = o.optBoolean("completed", false),
                    confidence = o.optDouble("confidence", 0.0),
                    rationale = rationale,
                    qualityScore = o.optDouble("quality_score", 0.0).coerceIn(0.0, 10.0),
                    recoveryEpisodes = episodes,
                    recoveryRate = authoritative,
                    source = "judge",
                    manualCompletedOverride = null,
                    manualRecoveryOverride = null
                )
            }.getOrNull()
        }

        private fun extractJson(raw: String): String? {
            val fence = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(raw)
            if (fence != null) return fence.groupValues[1].trim()
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start != -1 && end > start) return raw.substring(start, end + 1)
            return null
        }
    }
}
