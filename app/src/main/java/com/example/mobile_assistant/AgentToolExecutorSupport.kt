package com.example.mobile_assistant

import org.json.JSONObject

internal object AgentToolExecutorSupport {
    fun shouldAutoAttachObservation(toolName: String): Boolean {
        return toolName != AgentTooling.TOOL_READ_SCREEN &&
            toolName != AgentTooling.TOOL_SPEAK &&
            toolName != AgentTooling.TOOL_TASK_COMPLETE &&
            toolName != AgentTooling.TOOL_ASK_USER &&
            !SharedToolSchemas.isSharedTool(toolName)
    }

    fun appendObservationError(content: JSONObject, message: String) {
        val existing = content.optString("observation_error").trim()
        if (existing.isBlank()) {
            content.put("observation_error", message)
        } else if (!existing.contains(message)) {
            content.put("observation_error", "$existing $message")
        }
    }

    fun normalizeQuickQuestion(rawQuestion: String): String {
        val cleaned = rawQuestion.trim().ifBlank { "quick question: can you clarify what to do next?" }
        val lower = cleaned.lowercase()
        val prefixed = if (lower.startsWith("quick question")) cleaned else "quick question: $cleaned"
        return if ("reply in this chat" in prefixed.lowercase() || "reply here" in prefixed.lowercase()) {
            prefixed
        } else {
            "$prefixed Reply in this chat."
        }
    }

    fun result(
        toolCallId: String,
        content: JSONObject,
        shouldStopLoop: Boolean = false,
        hasUserFacingOutput: Boolean = false
    ): ToolExecutionResult {
        return ToolExecutionResult(
            toolMessage = JSONObject()
                .put("role", "tool")
                .put("tool_call_id", toolCallId)
                .put("content", content.toString()),
            shouldStopLoop = shouldStopLoop,
            hasUserFacingOutput = hasUserFacingOutput
        )
    }

    fun parseToolArguments(rawArguments: String, tag: String): JSONObject {
        return runCatching {
            if (rawArguments.isBlank()) JSONObject() else JSONObject(rawArguments)
        }.getOrDefault(JSONObject())
    }

    fun parseNodeRef(arguments: JSONObject): String? {
        return arguments.optString("node_ref").trim().ifBlank { null }
    }

    fun screenshotContentChanged(beforeDataUrl: String?, afterDataUrl: String?): Boolean {
        val before = beforeDataUrl?.trim()?.ifBlank { null }
        val after = afterDataUrl?.trim()?.ifBlank { null }
        if (before == null || after == null) return false
        return before != after
    }
}
