package com.example.mobile_assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects metrics for a single eval run. The agent feeds this via the global
 * [active] hook (guarded `EvalRecorder.active?....` so the normal app path is a no-op).
 *
 * Screenshots are only kept for failing steps and the final state; the heavy
 * decode/encode/write is deferred to [finalize] so the live agent loop never blocks.
 *
 * Nothing here is ever deleted: per-run JSON files, screenshots, and the
 * append-only [eval_database.txt] accumulate permanently. See plan §9.
 */
internal class EvalRecorder(context: Context) {

    private val appContext = context.applicationContext

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var runId: String = ""
    private var timestampMillis: Long = 0L
    private var taskId: String = ""
    private var goalText: String = ""
    private var successCriteria: String = ""
    private var agentModel: String = ""
    private var judgeModel: String = ""

    private var agentInvoked = false
    private var userStopped = false
    private val interventionReasons = mutableListOf<String>()
    private var finished = false

    private val steps = mutableListOf<MutableStep>()
    private val timeline = mutableListOf<EvalTimelineEvent>()
    private var nextSequence = 0

    /** Set when the run is fully recorded; EvalActivity polls this. */
    @Volatile
    var finishedRecord: EvalRunRecord? = null
        private set

    private class MutableStep(val index: Int) {
        var thinking: String? = null
        var errorShotDataUrl: String? = null
        val tools = mutableListOf<EvalToolCall>()
    }

    // ─── Live collection (called from AgentManager, must stay cheap) ──────────

    fun start(
        taskId: String,
        goalText: String,
        successCriteria: String,
        agentModel: String,
        judgeModel: String
    ) {
        this.timestampMillis = System.currentTimeMillis()
        this.runId = "run_$timestampMillis"
        this.taskId = taskId
        this.goalText = goalText
        this.successCriteria = successCriteria
        this.agentModel = agentModel
        this.judgeModel = judgeModel
        agentInvoked = false
        userStopped = false
        finished = false
        interventionReasons.clear()
        steps.clear()
        timeline.clear()
        nextSequence = 0
        finishedRecord = null
    }

    fun markAgentInvoked() {
        agentInvoked = true
    }

    fun beginStep(index: Int) {
        if (steps.lastOrNull()?.index == index) return
        steps.add(MutableStep(index))
    }

    fun recordStepThinking(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val step = steps.lastOrNull() ?: return
        step.thinking = trimmed
        timeline.add(
            EvalTimelineEvent(
                sequence = nextSequence++,
                stepIndex = step.index,
                type = "reasoning",
                reasoning = trimmed,
                toolCall = null
            )
        )
    }

    fun recordToolCall(
        stepIndex: Int,
        name: String,
        status: String,
        arguments: String,
        details: String
    ) {
        val step = steps.lastOrNull { it.index == stepIndex } ?: steps.lastOrNull() ?: return
        val toolCall = EvalToolCall(
            stepIndex = stepIndex,
            name = name,
            status = status,
            arguments = arguments,
            argsSummary = arguments.take(200),
            details = details
        )
        step.tools.add(toolCall)
        timeline.add(
            EvalTimelineEvent(
                sequence = nextSequence++,
                stepIndex = stepIndex,
                type = "tool_call",
                reasoning = null,
                toolCall = toolCall
            )
        )
    }

    /** kind = "error" (on a failing tool) or "final" (at finalize). Stores the raw
     *  data URL only; the actual file write happens later in [finalize]. */
    fun captureScreenshot(stepIndex: Int, kind: String, dataUrl: String?) {
        if (dataUrl.isNullOrBlank()) return
        if (kind == "final") {
            pendingFinalShotDataUrl = dataUrl
            return
        }
        val step = steps.lastOrNull { it.index == stepIndex } ?: steps.lastOrNull() ?: return
        if (step.errorShotDataUrl == null) step.errorShotDataUrl = dataUrl
    }

    private var pendingFinalShotDataUrl: String? = null

    fun markUserIntervened(reason: String) {
        if (reason !in interventionReasons) interventionReasons.add(reason)
    }

    fun markUserStopped() {
        userStopped = true
    }

    // ─── Finalization ────────────────────────────────────────────────────────

    fun finalizeIfUnfinished(stopReason: String, finalScreenshotDataUrl: String?) {
        if (finished) return
        finalize(
            stopReason = stopReason,
            taskCompleteFired = false,
            hardLoopDetected = false,
            maxConsecutiveSameToolCount = 0,
            transcript = emptyList(),
            finalScreenshotDataUrl = finalScreenshotDataUrl
        )
    }

    fun finalize(
        stopReason: String,
        taskCompleteFired: Boolean,
        hardLoopDetected: Boolean,
        maxConsecutiveSameToolCount: Int,
        transcript: List<ActionTranscriptEntry>,
        finalScreenshotDataUrl: String?
    ) {
        if (finished) return
        finished = true

        captureScreenshot(-1, "final", finalScreenshotDataUrl)

        val shotsDir = File(evalsDir(appContext), "shots").apply { mkdirs() }

        val stepRecords = steps.map { s ->
            val errorFile = s.errorShotDataUrl?.let {
                writeShot(shotsDir, "${runId}_error_step${pad(s.index)}.jpg", it)
            }
            EvalStepRecord(
                stepIndex = s.index,
                thinking = s.thinking,
                errorScreenshotFile = errorFile,
                toolCalls = s.tools.toList()
            )
        }
        val finalFile = pendingFinalShotDataUrl?.let {
            writeShot(shotsDir, "${runId}_final.jpg", it)
        }

        val record = EvalRunRecord(
            runId = runId,
            timestampMillis = timestampMillis,
            timestampIso = isoFormat.format(Date(timestampMillis)),
            taskId = taskId,
            goalText = goalText,
            successCriteria = successCriteria,
            agentModel = agentModel,
            judgeModel = judgeModel,
            agentInvoked = agentInvoked,
            stepCount = steps.size,
            stopReason = stopReason,
            taskCompleteFired = taskCompleteFired,
            hardLoopDetected = hardLoopDetected,
            maxConsecutiveSameToolCount = maxConsecutiveSameToolCount,
            userStopped = userStopped,
            userIntervened = interventionReasons.isNotEmpty(),
            interventionReasons = interventionReasons.toList(),
            finalScreenshotFile = finalFile,
            steps = stepRecords,
            toolCalls = stepRecords.flatMap { it.toolCalls },
            timeline = timeline.toList(),
            transcript = transcript.map { EvalTranscriptEntry(it.title, it.bodyMarkdown) },
            verdict = null
        )

        runCatching { writeRunFile(record) }
        finishedRecord = record
        if (active === this) active = null
    }

    // ─── File I/O ────────────────────────────────────────────────────────────

    private fun writeShot(shotsDir: File, fileName: String, dataUrl: String): String? {
        return runCatching {
            val marker = "base64,"
            val idx = dataUrl.indexOf(marker)
            if (idx == -1) return null
            val bytes = Base64.decode(dataUrl.substring(idx + marker.length), Base64.DEFAULT)
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = scaleToMax(src, MAX_SHOT_DIMEN)
            val out = File(shotsDir, fileName)
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, it) }
            if (scaled !== src) scaled.recycle()
            src.recycle()
            "shots/$fileName"
        }.getOrNull()
    }

    private fun scaleToMax(src: Bitmap, maxDimen: Int): Bitmap {
        val largest = maxOf(src.width, src.height)
        if (largest <= maxDimen) return src
        val ratio = maxDimen.toFloat() / largest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun writeRunFile(record: EvalRunRecord) {
        val dir = evalsDir(appContext).apply { mkdirs() }
        val target = File(dir, "${record.runId}.json")
        val tmp = File(dir, "${record.runId}.json.tmp")
        tmp.writeText(toJson(record).toString(2))
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    private fun pad(n: Int): String = n.toString().padStart(2, '0')

    // ─── JSON (pure; unit-tested via org.json) ───────────────────────────────

    companion object {
        @Volatile
        var active: EvalRecorder? = null

        private const val MAX_SHOT_DIMEN = 512
        private const val SHOT_QUALITY = 60
        private const val DB_DELIMITER =
            "\n========================= EVAL RUN =========================\n"

        fun evalsDir(context: Context): File =
            File(context.applicationContext.filesDir, "evals")

        fun shotsDir(context: Context): File =
            File(evalsDir(context), "shots")

        /** Attach a confirmed verdict, rewrite the run file, and append to the
         *  append-only eval database. Nothing is ever removed. */
        fun saveVerdict(context: Context, record: EvalRunRecord, verdict: EvalVerdict): EvalRunRecord {
            val updated = record.copy(verdict = verdict)
            val dir = evalsDir(context).apply { mkdirs() }
            val target = File(dir, "${updated.runId}.json")
            val tmp = File(dir, "${updated.runId}.json.tmp")
            tmp.writeText(toJson(updated).toString(2))
            if (target.exists()) target.delete()
            tmp.renameTo(target)

            File(dir, "eval_database.txt").appendText(
                DB_DELIMITER + toJson(updated).toString(2) + "\n"
            )
            rebuildIndex(context)
            return updated
        }

        fun loadAllRecords(context: Context): List<EvalRunRecord> {
            val dir = evalsDir(context)
            val files = dir.listFiles { f -> f.name.startsWith("run_") && f.name.endsWith(".json") }
                ?: return emptyList()
            return files.sortedBy { it.name }.mapNotNull { f ->
                runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
            }
        }

        private fun rebuildIndex(context: Context) {
            val records = loadAllRecords(context)
            val summaryArr = JSONArray()
            val fullRunsArr = JSONArray()
            records.forEach { r ->
                summaryArr.put(
                    JSONObject()
                        .put("run_id", r.runId)
                        .put("task_id", r.taskId)
                        .put("agent_model", r.agentModel)
                        .put("timestamp_iso", r.timestampIso)
                        .put("step_count", r.stepCount)
                        .put("quality_score", r.verdict?.qualityScore ?: JSONObject.NULL)
                        .put("user_stopped", r.userStopped)
                        .put("user_intervened", r.userIntervened)
                        .put("completed", r.verdict?.let { EvalScoring.effectiveCompleted(it) } ?: JSONObject.NULL)
                        .put("recovery_rate", r.verdict?.let { EvalScoring.effectiveRecoveryRate(it) } ?: JSONObject.NULL)
                )
                fullRunsArr.put(toJson(r))
            }
            val dir = evalsDir(context)
            File(dir, "index.json").writeText(summaryArr.toString(2))
            File(dir, "eval_runs.json").writeText(fullRunsArr.toString(2))
        }

        fun toJson(r: EvalRunRecord): JSONObject {
            val stepsArr = JSONArray()
            r.steps.forEach { s ->
                stepsArr.put(
                    JSONObject()
                        .put("step_index", s.stepIndex)
                        .put("thinking", s.thinking ?: JSONObject.NULL)
                        .put("error_screenshot_file", s.errorScreenshotFile ?: JSONObject.NULL)
                        .put("tool_calls", toolsArr(s.toolCalls))
                )
            }
            val transcriptArr = JSONArray()
            r.transcript.forEach {
                transcriptArr.put(
                    JSONObject().put("title", it.title).put("body_markdown", it.bodyMarkdown)
                )
            }
            val timelineArr = JSONArray()
            r.timeline.sortedBy { it.sequence }.forEach { e ->
                timelineArr.put(
                    JSONObject()
                        .put("sequence", e.sequence)
                        .put("step_index", e.stepIndex)
                        .put("type", e.type)
                        .put("reasoning", e.reasoning ?: JSONObject.NULL)
                        .put("tool_call", e.toolCall?.let { toolToJson(it) } ?: JSONObject.NULL)
                )
            }
            return JSONObject()
                .put("schema_version", EVAL_SCHEMA_VERSION)
                .put("run_id", r.runId)
                .put("timestamp_millis", r.timestampMillis)
                .put("timestamp_iso", r.timestampIso)
                .put("task_id", r.taskId)
                .put("goal_text", r.goalText)
                .put("success_criteria", r.successCriteria)
                .put("agent_model", r.agentModel)
                .put("judge_model", r.judgeModel)
                .put("agent_invoked", r.agentInvoked)
                .put("step_count", r.stepCount)
                .put("stop_reason", r.stopReason)
                .put("task_complete_fired", r.taskCompleteFired)
                .put("hard_loop_detected", r.hardLoopDetected)
                .put("max_consecutive_same_tool_count", r.maxConsecutiveSameToolCount)
                .put("user_stopped", r.userStopped)
                .put("user_intervened", r.userIntervened)
                .put("intervention_reasons", JSONArray(r.interventionReasons))
                .put("final_screenshot_file", r.finalScreenshotFile ?: JSONObject.NULL)
                .put("steps", stepsArr)
                .put("tool_calls", toolsArr(r.toolCalls))
                .put("timeline", timelineArr)
                .put("transcript", transcriptArr)
                .put("verdict", r.verdict?.let { verdictToJson(it) } ?: JSONObject.NULL)
        }

        private fun toolsArr(tools: List<EvalToolCall>): JSONArray {
            val arr = JSONArray()
            tools.forEach { t ->
                arr.put(
                    toolToJson(t)
                )
            }
            return arr
        }

        private fun toolToJson(t: EvalToolCall): JSONObject =
            JSONObject()
                .put("step_index", t.stepIndex)
                .put("name", t.name)
                .put("status", t.status)
                .put("arguments", t.arguments)
                .put("args_summary", t.argsSummary)
                .put("details", t.details)

        private fun verdictToJson(v: EvalVerdict): JSONObject {
            val eps = JSONArray()
            v.recoveryEpisodes.forEach { e ->
                eps.put(
                    JSONObject()
                        .put("step", e.step)
                        .put("source", e.source)
                        .put("what_went_wrong", e.whatWentWrong)
                        .put("detected", e.detected)
                        .put("steps_to_recover", e.stepsToRecover)
                        .put("outcome", e.outcome)
                )
            }
            return JSONObject()
                .put("completed", v.completed)
                .put("confidence", v.confidence)
                .put("rationale", v.rationale)
                .put("quality_score", v.qualityScore)
                .put("recovery_rate", v.recoveryRate)
                .put("source", v.source)
                .put("manual_completed_override", v.manualCompletedOverride ?: JSONObject.NULL)
                .put("manual_recovery_override", v.manualRecoveryOverride ?: JSONObject.NULL)
                .put("recovery_episodes", eps)
        }

        fun fromJson(o: JSONObject): EvalRunRecord {
            return EvalRunRecord(
                runId = o.optString("run_id"),
                timestampMillis = o.optLong("timestamp_millis"),
                timestampIso = o.optString("timestamp_iso"),
                taskId = o.optString("task_id"),
                goalText = o.optString("goal_text"),
                successCriteria = o.optString("success_criteria"),
                agentModel = o.optString("agent_model"),
                judgeModel = o.optString("judge_model"),
                agentInvoked = o.optBoolean("agent_invoked"),
                stepCount = o.optInt("step_count"),
                stopReason = o.optString("stop_reason"),
                taskCompleteFired = o.optBoolean("task_complete_fired"),
                hardLoopDetected = o.optBoolean("hard_loop_detected"),
                maxConsecutiveSameToolCount = o.optInt("max_consecutive_same_tool_count"),
                userStopped = o.optBoolean("user_stopped"),
                userIntervened = o.optBoolean("user_intervened"),
                interventionReasons = stringList(o.optJSONArray("intervention_reasons")),
                finalScreenshotFile = o.optStringOrNull("final_screenshot_file"),
                steps = parseSteps(o.optJSONArray("steps")),
                toolCalls = parseTools(o.optJSONArray("tool_calls")),
                timeline = parseTimeline(o.optJSONArray("timeline")),
                transcript = parseTranscript(o.optJSONArray("transcript")),
                verdict = o.optJSONObject("verdict")?.let { parseVerdict(it) }
            )
        }

        private fun parseSteps(arr: JSONArray?): List<EvalStepRecord> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { s ->
                EvalStepRecord(
                    stepIndex = s.optInt("step_index"),
                    thinking = s.optStringOrNull("thinking"),
                    errorScreenshotFile = s.optStringOrNull("error_screenshot_file"),
                    toolCalls = parseTools(s.optJSONArray("tool_calls"))
                )
            }
        }

        private fun parseTools(arr: JSONArray?): List<EvalToolCall> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { t ->
                EvalToolCall(
                    stepIndex = t.optInt("step_index"),
                    name = t.optString("name"),
                    status = t.optString("status"),
                    arguments = t.optString("arguments", t.optString("args_summary")),
                    argsSummary = t.optString("args_summary"),
                    details = t.optString("details")
                )
            }
        }

        private fun parseTimeline(arr: JSONArray?): List<EvalTimelineEvent> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { e ->
                EvalTimelineEvent(
                    sequence = e.optInt("sequence"),
                    stepIndex = e.optInt("step_index"),
                    type = e.optString("type"),
                    reasoning = e.optStringOrNull("reasoning"),
                    toolCall = e.optJSONObject("tool_call")?.let { parseTools(JSONArray().put(it)).firstOrNull() }
                )
            }
        }

        private fun parseTranscript(arr: JSONArray?): List<EvalTranscriptEntry> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                EvalTranscriptEntry(it.optString("title"), it.optString("body_markdown"))
            }
        }

        private fun parseVerdict(v: JSONObject): EvalVerdict {
            val eps = v.optJSONArray("recovery_episodes")
            val episodes = if (eps == null) emptyList() else
                (0 until eps.length()).mapNotNull { eps.optJSONObject(it) }.map { e ->
                    RecoveryEpisode(
                        step = e.optInt("step"),
                        source = e.optString("source"),
                        whatWentWrong = e.optString("what_went_wrong"),
                        detected = e.optBoolean("detected"),
                        stepsToRecover = e.optInt("steps_to_recover", -1),
                        outcome = e.optString("outcome")
                    )
                }
            return EvalVerdict(
                completed = v.optBoolean("completed"),
                confidence = v.optDouble("confidence", 0.0),
                rationale = v.optString("rationale"),
                qualityScore = v.optDouble("quality_score", 0.0).coerceIn(0.0, 10.0),
                recoveryEpisodes = episodes,
                recoveryRate = v.optDouble("recovery_rate", 1.0),
                source = v.optString("source", "judge"),
                manualCompletedOverride = if (v.isNull("manual_completed_override")) null
                else v.optBoolean("manual_completed_override"),
                manualRecoveryOverride = if (v.isNull("manual_recovery_override")) null
                else v.optDouble("manual_recovery_override")
            )
        }

        private fun stringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifBlank { null }
    }
}
