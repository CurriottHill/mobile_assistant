package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal object AgentApiSupport {
    private val CITATION_REGEX = Regex("""\u3010[^】]*\u3011""")
    private val WHITESPACE_REGEX = Regex("""\s{2,}""")
    private val ERROR_WHITESPACE_REGEX = Regex("""\s+""")

    fun parseResponsesApiResult(responseJson: JSONObject): JSONObject {
        val output = responseJson.optJSONArray("output") ?: JSONArray()
        val textParts = StringBuilder()
        val toolCalls = JSONArray()

        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val type = item.optString("type")

            when (type) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") {
                            textParts.append(part.optString("text"))
                        }
                    }
                }

                "function_call" -> {
                    toolCalls.put(
                        JSONObject()
                            .put("id", item.optString("id"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", item.optString("name"))
                                    .put("arguments", item.optString("arguments"))
                            )
                    )
                }
            }
        }

        val result = JSONObject()
            .put("role", "assistant")
            .put("content", stripCitationMarkers(textParts.toString()).ifBlank { JSONObject.NULL })

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

    fun formatAgentApiError(
        provider: ModelProvider,
        model: String,
        statusCode: Int,
        responseBody: String,
        maxBodyChars: Int
    ): String {
        val parsed = runCatching { JSONObject(responseBody) }.getOrNull()
        val providerLabel = provider.name.lowercase()
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
}
