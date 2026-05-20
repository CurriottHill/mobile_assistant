package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal object ChatToolLoopSupport {
    fun toolResultBlock(
        toolUseId: String,
        resultContent: JSONObject,
        isError: Boolean
    ): JSONObject {
        return JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", toolUseId)
            .put("content", resultContent.toString())
            .put("is_error", isError)
    }

    fun errorResultContent(
        toolName: String,
        error: String,
        skipped: Boolean = false
    ): JSONObject {
        return JSONObject()
            .put("tool", toolName)
            .put("ok", false)
            .put("error", error)
            .also { content ->
                if (skipped) {
                    content.put("skipped", true)
                }
            }
    }

    fun appendSkippedToolResults(
        toolCalls: JSONArray,
        startIndex: Int,
        toolResults: JSONArray,
        reason: String
    ) {
        for (index in startIndex until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(index) ?: continue
            val toolUseId = toolCall.optString("id").ifBlank { "chat_tool_$index" }
            val function = toolCall.optJSONObject("function")
            val toolName = function?.optString("name")?.trim().orEmpty().ifBlank { "unknown_tool" }
            toolResults.put(
                toolResultBlock(
                    toolUseId = toolUseId,
                    resultContent = errorResultContent(
                        toolName = toolName,
                        error = reason,
                        skipped = true
                    ),
                    isError = true
                )
            )
        }
    }
}
