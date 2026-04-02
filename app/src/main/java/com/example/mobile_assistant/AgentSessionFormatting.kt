package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal data class StructuredAgentResponse(
    val thinking: String,
    val mission: ParsedMission?,
    val nextSteps: List<NextStep>,
    val actions: List<JSONObject>
)

internal data class ParsedMission(
    val goal: String,
    val phases: List<PlanStep>
)

internal data class ToolCallSummary(
    val name: String,
    val status: String,
    val details: String
)

internal data class ActionTranscriptEntry(
    val title: String,
    val bodyMarkdown: String
)

internal object AgentSessionFormatting {
    fun parseStructuredAgentResponse(raw: String): StructuredAgentResponse {
        val jsonStr = extractJsonFromResponse(raw)
        return runCatching {
            val json = JSONObject(jsonStr)
            val thinking = json.optString("thinking", "")

            val missionJson = json.optJSONObject("mission")
            val mission = missionJson?.let { parseMissionJson(it) }

            val nextStepsJson = json.optJSONArray("next_steps")
            val nextSteps = if (nextStepsJson != null) parseNextStepsJson(nextStepsJson) else emptyList()

            val actionsJson = json.optJSONArray("actions")
            val actions: List<JSONObject> = when {
                actionsJson != null -> (0 until actionsJson.length()).mapNotNull { actionsJson.optJSONObject(it) }
                else -> json.optJSONObject("action")?.let { listOf(it) } ?: emptyList()
            }

            StructuredAgentResponse(thinking = thinking, mission = mission, nextSteps = nextSteps, actions = actions)
        }.getOrElse {
            StructuredAgentResponse(thinking = raw, mission = null, nextSteps = emptyList(), actions = emptyList())
        }
    }

    fun renderStateJson(
        goal: String,
        plan: List<PlanStep>,
        nextSteps: List<NextStep>,
        screenObservation: ScreenObservationState?
    ): String {
        return JSONObject()
            .put(
                "mission",
                renderMissionJsonObject(
                    goal = goal,
                    plan = plan
                )
            )
            .put("next_steps", renderNextStepsJsonArray(nextSteps))
            .put("state", renderScreenStateJsonObject(screenObservation))
            .put("action", JSONObject())
            .toString(2)
    }

    fun renderMemoryMarkdown(memory: List<MemoryEntry>, recentMemoryLimit: Int): String {
        if (memory.isEmpty()) return "- (none)"
        return memory.takeLast(recentMemoryLimit).joinToString("\n") { entry ->
            "- `${entry.action}` -> ${entry.result}"
        }
    }

    fun buildActionTranscriptMessage(entries: List<ActionTranscriptEntry>): String? {
        if (entries.isEmpty()) return null
        return buildString {
            appendLine("## Action Transcript")
            appendLine("This is the running Markdown transcript of the current phone task.")
            appendLine()
            entries.forEachIndexed { index, entry ->
                appendLine("### ${index + 1}. ${entry.title}")
                appendLine(entry.bodyMarkdown.trim())
                if (index < entries.lastIndex) {
                    appendLine()
                }
            }
        }.trim()
    }

    fun buildActionTranscriptToolBody(rawArgs: String, content: JSONObject): String {
        val parsedArguments = runCatching {
            if (rawArgs.isBlank()) JSONObject() else JSONObject(rawArgs)
        }.getOrElse { rawArgs }
        val transcriptContent = sanitizeToolContentForTranscript(content)
        return buildString {
            appendLine("#### Arguments")
            appendLine(
                JsonDisplayFormatter.formatMarkdownCodeBlock(
                    parsedArguments,
                    if (parsedArguments is JSONObject || parsedArguments is JSONArray) "json" else ""
                )
            )
            appendLine()
            appendLine("#### Result")
            append(JsonDisplayFormatter.formatMarkdownCodeBlock(transcriptContent, "json"))
        }.trim()
    }

    fun compactMarkdownText(text: String): String {
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    fun buildToolOutcomeDetails(toolName: String, rawArgs: String, content: JSONObject): String {
        val args = runCatching { JSONObject(rawArgs) }.getOrNull()
        return when (toolName) {
            AgentTooling.TOOL_OPEN_APP -> {
                val requested = args?.optString("name").orEmpty()
                val resolved = content.optString("resolved_label").ifBlank {
                    content.optString("package_name")
                }
                val observed = content.optString("observed_package")
                val waitStatus = content.optString("wait_status")
                listOfNotNull(
                    requested.takeIf { it.isNotBlank() }?.let { "name=$it" },
                    resolved.takeIf { it.isNotBlank() }?.let { "resolved=$it" },
                    observed.takeIf { it.isNotBlank() }?.let { "observed=$it" },
                    waitStatus.takeIf { it.isNotBlank() }?.let { "wait=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_OPEN_URL -> {
                val requested = args?.optString("url").orEmpty()
                val opened = content.optString("opened_url")
                val resolved = content.optString("resolved_label").ifBlank {
                    content.optString("package_name")
                }
                val observed = content.optString("observed_package")
                val waitStatus = content.optString("wait_status")
                listOfNotNull(
                    requested.takeIf { it.isNotBlank() }?.let { "url=$it" },
                    opened.takeIf { it.isNotBlank() }?.let { "opened=$it" },
                    resolved.takeIf { it.isNotBlank() }?.let { "resolved=$it" },
                    observed.takeIf { it.isNotBlank() }?.let { "observed=$it" },
                    waitStatus.takeIf { it.isNotBlank() }?.let { "wait=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_TAP_NODE -> {
                val nodeRef = extractNodeRef(args)
                val resolvedNodeRef = content.optString("resolved_node_ref")
                val resolved = content.optString("resolved_label")
                val matchedNodeRef = content.optString("matched_node_ref")
                listOfNotNull(
                    nodeRef?.let { "node_ref=$it" },
                    resolvedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "resolved=$it" },
                    matchedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "matched=$it" },
                    resolved.takeIf { it.isNotBlank() }?.let { "target=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_SCROLL -> {
                val nodeRef = extractNodeRef(args)
                val direction = args?.optString("direction").orEmpty()
                val resolvedNodeRef = content.optString("resolved_node_ref")
                val resolved = content.optString("resolved_label")
                val matchedNodeRef = content.optString("matched_node_ref")
                listOfNotNull(
                    nodeRef?.let { "node_ref=$it" },
                    direction.takeIf { it.isNotBlank() }?.let { "direction=$it" },
                    resolvedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "resolved=$it" },
                    matchedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "matched=$it" },
                    resolved.takeIf { it.isNotBlank() }?.let { "target=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_SCROLL_PAGE -> {
                val direction = content.optString("direction")
                val pkg = content.optString("package_name")
                val waitStatus = content.optString("wait_status")
                listOfNotNull(
                    direction.takeIf { it.isNotBlank() }?.let { "direction=$it" },
                    pkg.takeIf { it.isNotBlank() }?.let { "package=$it" },
                    waitStatus.takeIf { it.isNotBlank() }?.let { "wait=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_TAP_XY -> {
                val x = args?.opt("x")?.toString()?.trim()
                val y = args?.opt("y")?.toString()?.trim()
                val xPx = content.optInt("x_px").takeIf { content.has("x_px") }
                val yPx = content.optInt("y_px").takeIf { content.has("y_px") }
                listOfNotNull(
                    x?.takeIf { it.isNotBlank() }?.let { "x=$it" },
                    y?.takeIf { it.isNotBlank() }?.let { "y=$it" },
                    xPx?.let { "x_px=$it" },
                    yPx?.let { "y_px=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_LONG_PRESS_NODE -> {
                val nodeRef = extractNodeRef(args)
                val resolvedNodeRef = content.optString("resolved_node_ref")
                val resolved = content.optString("resolved_label")
                val matchedNodeRef = content.optString("matched_node_ref")
                listOfNotNull(
                    nodeRef?.let { "node_ref=$it" },
                    resolvedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "resolved=$it" },
                    matchedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "matched=$it" },
                    resolved.takeIf { it.isNotBlank() }?.let { "target=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_TAP_TYPE_TEXT -> {
                val nodeRef = extractNodeRef(args)
                val resolvedNodeRef = content.optString("resolved_node_ref")
                val resolved = content.optString("resolved_label")
                val matchedNodeRef = content.optString("matched_node_ref")
                val typedNodeRef = content.optString("typed_node_ref")
                val text = args?.optString("text").orEmpty().take(40)
                listOfNotNull(
                    nodeRef?.let { "node_ref=$it" },
                    resolvedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "resolved=$it" },
                    matchedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "matched=$it" },
                    typedNodeRef.takeIf { it.isNotBlank() && it != matchedNodeRef }?.let { "typed=$it" },
                    resolved.takeIf { it.isNotBlank() }?.let { "target=$it" },
                    text.takeIf { it.isNotBlank() }?.let { "text=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_TYPE_TEXT -> {
                val nodeRef = extractNodeRef(args)
                val resolvedNodeRef = content.optString("resolved_node_ref")
                val resolved = content.optString("resolved_label")
                val matchedNodeRef = content.optString("matched_node_ref")
                val text = args?.optString("text").orEmpty().take(40)
                listOfNotNull(
                    nodeRef?.let { "node_ref=$it" },
                    resolvedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "resolved=$it" },
                    matchedNodeRef.takeIf { it.isNotBlank() && it != nodeRef }?.let { "matched=$it" },
                    resolved.takeIf { it.isNotBlank() }?.let { "target=$it" },
                    text.takeIf { it.isNotBlank() }?.let { "text=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_READ_SCREEN -> {
                val pkg = content.optString("package_name")
                listOfNotNull(
                    pkg.takeIf { it.isNotBlank() }?.let { "package=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_GO_BACK -> {
                val action = content.optString("action")
                val pkg = content.optString("foreground_package")
                val waitStatus = content.optString("wait_status")
                listOfNotNull(
                    action.takeIf { it.isNotBlank() }?.let { "action=$it" },
                    pkg.takeIf { it.isNotBlank() }?.let { "package=$it" },
                    waitStatus.takeIf { it.isNotBlank() }?.let { "wait=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_SWIPE -> {
                val direction = content.optString("direction")
                val pkg = content.optString("package_name")
                val waitStatus = content.optString("wait_status")
                listOfNotNull(
                    direction.takeIf { it.isNotBlank() }?.let { "direction=$it" },
                    pkg.takeIf { it.isNotBlank() }?.let { "package=$it" },
                    waitStatus.takeIf { it.isNotBlank() }?.let { "wait=$it" }
                ).joinToString(", ")
            }

            AgentTooling.TOOL_ASK_USER -> args?.optString("question").orEmpty().take(80)
            AgentTooling.TOOL_SPEAK -> args?.optString("message").orEmpty().take(80)
            AgentTooling.TOOL_TASK_COMPLETE -> args?.optString("summary").orEmpty().take(80)
            else -> ""
        }
    }

    private fun extractJsonFromResponse(raw: String): String {
        val fenceMatch = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(raw)
        if (fenceMatch != null) return fenceMatch.groupValues[1].trim()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start != -1 && end > start) return raw.substring(start, end + 1)
        return raw
    }

    private fun parseMissionJson(json: JSONObject): ParsedMission {
        val goal = json.optString("goal", "")
        val phasesJson = json.optJSONArray("phases") ?: JSONArray()
        val phases = mutableListOf<PlanStep>()
        for (i in 0 until phasesJson.length()) {
            val phaseJson = phasesJson.optJSONObject(i) ?: continue
            val id = phaseJson.optInt("id", i + 1)
            val title = phaseJson.optString("title", "").trim()
            val statusStr = phaseJson.optString("status", "pending")
            val status = when (statusStr.lowercase()) {
                "done" -> StepStatus.DONE
                "in_progress", "active" -> StepStatus.ACTIVE
                "failed" -> StepStatus.FAILED
                else -> StepStatus.PENDING
            }
            if (title.isNotBlank() && !title.equals("(none)", ignoreCase = true)) {
                phases.add(PlanStep(id = id, action = title, expectedOutcome = "", status = status))
            }
        }
        return ParsedMission(
            goal = goal,
            phases = phases
        )
    }

    private fun parseNextStepsJson(json: JSONArray): List<NextStep> {
        val steps = mutableListOf<NextStep>()
        for (i in 0 until json.length()) {
            val item = json.optJSONObject(i) ?: continue
            val done = item.optBoolean("done", false)
            val text = item.optString("text", "").trim()
            if (text.isNotBlank()) steps.add(NextStep(done = done, text = text))
        }
        return steps
    }

    private fun renderMissionJsonObject(goal: String, plan: List<PlanStep>): JSONObject {
        val phasesArray = JSONArray()
        plan.forEach { step ->
            val statusStr = when (step.status) {
                StepStatus.DONE -> "done"
                StepStatus.ACTIVE -> "in_progress"
                StepStatus.FAILED -> "failed"
                StepStatus.PENDING -> "pending"
            }
            phasesArray.put(
                JSONObject()
                    .put("id", step.id)
                    .put("title", step.action)
                    .put("status", statusStr)
            )
        }
        if (phasesArray.length() == 0) {
            phasesArray.put(JSONObject().put("id", 1).put("title", "(none)").put("status", "pending"))
        }
        return JSONObject()
            .put("goal", goal.ifBlank { "(none)" })
            .put("phases", phasesArray)
    }

    private fun renderNextStepsJsonArray(nextSteps: List<NextStep>): JSONArray {
        val array = JSONArray()
        nextSteps.forEach { step ->
            array.put(JSONObject().put("done", step.done).put("text", step.text))
        }
        if (array.length() == 0) {
            array.put(JSONObject().put("done", false).put("text", "(none)"))
        }
        return array
    }

    private fun renderScreenStateJsonObject(screenObservation: ScreenObservationState?): JSONObject {
        return JSONObject()
            .put("package_name", screenObservation?.packageName ?: JSONObject.NULL)
            .put("image_revision", screenObservation?.revision ?: JSONObject.NULL)
    }

    private fun sanitizeToolContentForTranscript(content: JSONObject): JSONObject {
        val copy = JSONObject(content.toString())
        if (copy.has("screen")) {
            copy.put("screen", "[latest accessibility tree omitted here and provided separately]")
        }
        if (copy.has("image_data_url")) {
            copy.put("image_data_url", "[latest screenshot omitted here and provided separately]")
        }
        listOf(
            "width",
            "height",
            "screen_width",
            "screen_height",
            "screen_left",
            "screen_top",
            "x_px",
            "y_px",
            "debug"
        ).forEach(copy::remove)
        return copy
    }

    private fun extractNodeRef(args: JSONObject?): String? {
        if (args == null) return null
        return args.optString("node_ref").trim().ifBlank { null }
    }
}
