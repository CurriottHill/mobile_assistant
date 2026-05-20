package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal object AgentApiSupport {
    private val CITATION_REGEX = Regex("""【[^】]*】""")
    private val WHITESPACE_REGEX = Regex("""\s{2,}""")
    private val ERROR_WHITESPACE_REGEX = Regex("""\s+""")

    /**
     * Parse an Anthropic Messages API response that may contain assistant text
     * and/or tool_use blocks into {role, content, tool_calls}. tool_calls are
     * normalized to the OpenAI-style shape the chat router already understands.
     */
    fun parseAnthropicChatResult(responseJson: JSONObject): JSONObject {
        val content = responseJson.optJSONArray("content") ?: JSONArray()
        val textParts = StringBuilder()
        val toolCalls = JSONArray()

        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> textParts.append(block.optString("text"))
                "tool_use" -> {
                    val inputObj = block.optJSONObject("input") ?: JSONObject()
                    toolCalls.put(
                        JSONObject()
                            .put("id", block.optString("id"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", block.optString("name"))
                                    .put("arguments", inputObj.toString())
                            )
                    )
                }
            }
        }

        val result = JSONObject()
            .put("role", "assistant")
            .put("content", stripCitationMarkers(textParts.toString()).ifBlank { JSONObject.NULL })
            .put("assistant_content", JSONArray(content.toString()))
        if (toolCalls.length() > 0) {
            result.put("tool_calls", toolCalls)
        }
        return result
    }

    fun parseAnthropicMessagesApiResult(responseJson: JSONObject): JSONObject {
        val content = responseJson.optJSONArray("content") ?: JSONArray()
        val textParts = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") {
                textParts.append(part.optString("text"))
            }
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", textParts.toString().trim().ifBlank { JSONObject.NULL })
    }

    fun parseOpenAiChatCompletionResult(responseJson: JSONObject): JSONObject {
        val message = responseJson
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: JSONObject()
        val contentText = message.optString("content").trim()
        val toolCalls = message.optJSONArray("tool_calls")
        val assistantContent = JSONArray()

        if (contentText.isNotBlank()) {
            assistantContent.put(JSONObject().put("type", "text").put("text", contentText))
        }
        toolCalls?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val input = runCatching {
                    JSONObject(function.optString("arguments", "{}"))
                }.getOrDefault(JSONObject())
                assistantContent.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", call.optString("id"))
                        .put("name", function.optString("name"))
                        .put("input", input)
                )
            }
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", contentText.ifBlank { JSONObject.NULL })
            .also { result ->
                assistantContent.takeIf { it.length() > 0 }?.let {
                    result.put("assistant_content", it)
                }
                toolCalls?.takeIf { it.length() > 0 }?.let { toolCalls ->
                    result.put("tool_calls", toolCalls)
                }
            }
    }

    fun parseOpenAiChatCompletionText(responseJson: JSONObject): JSONObject {
        val message = responseJson
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: JSONObject()

        return JSONObject()
            .put("role", "assistant")
            .put("content", message.optString("content").trim().ifBlank { JSONObject.NULL })
    }

    /**
     * Convert the app's internal chat history into OpenAI Chat Completions messages without
     * flattening tool calls into plain text. This preserves assistant tool requests and tool
     * results across rounds so the fallback model can continue a tool loop reliably.
     */
    fun buildOpenAiChatMessages(systemPrompt: String, chatHistory: List<JSONObject>): JSONArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))

        for (msg in chatHistory) {
            val role = msg.optString("role").ifBlank { "user" }
            when (val content = msg.opt("content")) {
                is JSONArray -> appendStructuredOpenAiMessages(messages, role, content)
                null, JSONObject.NULL -> continue
                else -> {
                    val text = content.toString().trim()
                    if (text.isBlank()) continue
                    messages.put(JSONObject().put("role", role).put("content", text))
                }
            }
        }

        return messages
    }

    fun formatAgentApiError(
        providerLabel: String,
        model: String,
        statusCode: Int,
        responseBody: String,
        maxBodyChars: Int
    ): String {
        val parsed = runCatching { JSONObject(responseBody) }.getOrNull()
        val requestId = parsed?.optString("request_id").orEmpty().ifBlank { null }
        val apiMessage = parsed?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { null }
        val rawBody = compactApiErrorBody(responseBody, maxBodyChars)

        return buildString {
            append("Agent API error")
            append(" provider=")
            append(providerLabel)
            append(" model=")
            append(model)
            append(" http=")
            append(statusCode)
            requestId?.let {
                append(" request_id=")
                append(it)
            }
            apiMessage?.let {
                append(" message=")
                append(it)
            }
            if (apiMessage == null && rawBody.isNotBlank()) {
                append(" body=")
                append(rawBody)
            }
        }
    }

    fun compactApiErrorBody(responseBody: String, maxBodyChars: Int): String {
        return responseBody
            .replace(ERROR_WHITESPACE_REGEX, " ")
            .trim()
            .take(maxBodyChars)
    }

    fun stripCitationMarkers(text: String): String {
        return text
            .replace(CITATION_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun appendStructuredOpenAiMessages(
        messages: JSONArray,
        role: String,
        content: JSONArray
    ) {
        when (role) {
            "assistant" -> appendStructuredAssistantMessage(messages, content)
            "user" -> appendStructuredUserMessage(messages, content)
            else -> {
                val text = flattenStructuredText(content)
                if (text.isNotBlank()) {
                    messages.put(JSONObject().put("role", role).put("content", text))
                }
            }
        }
    }

    private fun appendStructuredAssistantMessage(messages: JSONArray, content: JSONArray) {
        val textParts = StringBuilder()
        val toolCalls = JSONArray()

        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val text = block.optString("text").trim()
                    if (text.isBlank()) continue
                    if (textParts.isNotEmpty()) textParts.append("\n\n")
                    textParts.append(text)
                }
                "tool_use" -> {
                    val input = block.optJSONObject("input") ?: JSONObject()
                    toolCalls.put(
                        JSONObject()
                            .put("id", block.optString("id"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", block.optString("name"))
                                    .put("arguments", input.toString())
                            )
                    )
                }
            }
        }

        if (textParts.isEmpty() && toolCalls.length() == 0) return

        messages.put(
            JSONObject()
                .put("role", "assistant")
                .put(
                    "content",
                    textParts.toString().ifBlank {
                        if (toolCalls.length() > 0) JSONObject.NULL.toString() else ""
                    }
                )
                .also { message ->
                    if (textParts.isEmpty() && toolCalls.length() > 0) {
                        message.put("content", JSONObject.NULL)
                    }
                    if (toolCalls.length() > 0) {
                        message.put("tool_calls", toolCalls)
                    }
                }
        )
    }

    private fun appendStructuredUserMessage(messages: JSONArray, content: JSONArray) {
        val textParts = StringBuilder()

        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val text = block.optString("text").trim()
                    if (text.isBlank()) continue
                    if (textParts.isNotEmpty()) textParts.append("\n\n")
                    textParts.append(text)
                }
                "tool_result" -> {
                    val toolCallId = block.optString("tool_use_id").trim()
                    if (toolCallId.isBlank()) continue
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", toolCallId)
                            .put("content", block.optString("content"))
                    )
                }
            }
        }

        if (textParts.isNotEmpty()) {
            messages.put(JSONObject().put("role", "user").put("content", textParts.toString()))
        }
    }

    private fun flattenStructuredText(content: JSONArray): String {
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") != "text") continue
                val text = block.optString("text").trim()
                if (text.isBlank()) continue
                if (isNotEmpty()) append("\n\n")
                append(text)
            }
        }.trim()
    }
}
