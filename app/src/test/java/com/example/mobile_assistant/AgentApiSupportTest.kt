package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class AgentApiSupportTest {
    @Test
    fun stripCitationMarkers_removesInlineCitationsAndCompactsWhitespace() {
        assertEquals(
            "Answer with extra spacing.",
            AgentApiSupport.stripCitationMarkers("Answer \u30104:0\u2020source\u3011 with   extra spacing.")
        )
    }

    @Test
    fun compactApiErrorBody_flattensWhitespaceAndTruncates() {
        assertEquals(
            "line one line",
            AgentApiSupport.compactApiErrorBody(" line one\n\nline two ", maxBodyChars = 13)
        )
    }

    @Test
    fun parseAnthropicChatResult_preservesAssistantContentAndToolCalls() {
        val responseJson = JSONObject(
            """
            {
              "content": [
                { "type": "text", "text": "Working on it" },
                {
                  "type": "tool_use",
                  "id": "toolu_123",
                  "name": "spotify_control_playback",
                  "input": { "action": "next" }
                }
              ]
            }
            """.trimIndent()
        )

        val parsed = AgentApiSupport.parseAnthropicChatResult(responseJson)
        val assistantContent = parsed.getJSONArray("assistant_content")
        val toolCalls = parsed.getJSONArray("tool_calls")

        assertEquals(2, assistantContent.length())
        assertEquals("toolu_123", toolCalls.getJSONObject(0).getString("id"))
        assertTrue(parsed.getString("content").contains("Working on it"))
    }

    @Test
    fun parseOpenAiChatCompletionResult_preservesToolCalls() {
        val responseJson = JSONObject(
            """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Sure",
                    "tool_calls": [
                      {
                        "id": "call_123",
                        "type": "function",
                        "function": {
                          "name": "openapp",
                          "arguments": "{\"name\":\"spotify\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val parsed = AgentApiSupport.parseOpenAiChatCompletionResult(responseJson)
        val assistantContent = parsed.getJSONArray("assistant_content")

        assertEquals("Sure", parsed.getString("content"))
        assertEquals("call_123", parsed.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool_use", assistantContent.getJSONObject(1).getString("type"))
        assertEquals("spotify", assistantContent.getJSONObject(1).getJSONObject("input").getString("name"))
    }

    @Test
    fun buildOpenAiChatMessages_preservesToolCallsAndToolResults() {
        val history = listOf(
            JSONObject()
                .put("role", "user")
                .put("content", "Open Spotify"),
            JSONObject()
                .put(
                    "role",
                    "assistant"
                )
                .put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "Opening it now"))
                        .put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", "call_123")
                                .put("name", "openapp")
                                .put("input", JSONObject().put("name", "spotify"))
                        )
                ),
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "call_123")
                                .put("content", "{\"ok\":true}")
                                .put("is_error", false)
                        )
                )
        )

        val messages = AgentApiSupport.buildOpenAiChatMessages(
            systemPrompt = "System prompt",
            chatHistory = history
        )

        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Opening it now", messages.getJSONObject(2).getString("content"))
        assertEquals(
            "call_123",
            messages.getJSONObject(2)
                .getJSONArray("tool_calls")
                .getJSONObject(0)
                .getString("id")
        )
        assertEquals("tool", messages.getJSONObject(3).getString("role"))
        assertEquals("call_123", messages.getJSONObject(3).getString("tool_call_id"))
        assertEquals("{\"ok\":true}", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun buildOpenAiChatMessages_usesNullContentForToolOnlyAssistantTurn() {
        val history = listOf(
            JSONObject()
                .put("role", "assistant")
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", "call_456")
                            .put("name", "clipboard_get")
                            .put("input", JSONObject())
                    )
                )
        )

        val messages = AgentApiSupport.buildOpenAiChatMessages(
            systemPrompt = "System prompt",
            chatHistory = history
        )

        assertTrue(messages.getJSONObject(1).has("tool_calls"))
        assertTrue(messages.getJSONObject(1).isNull("content"))
        assertNull(messages.getJSONObject(1).opt("content").takeUnless { it == JSONObject.NULL })
    }

    @Test
    fun openAiFallbackModelFor_mapsAnthropicFamilies() {
        assertEquals(
            "gpt-5-mini",
            AgentModelConfig.openAiFallbackModelFor("claude-haiku-4-5-20251001")
        )
        assertEquals(
            "gpt-5.4",
            AgentModelConfig.openAiFallbackModelFor("claude-sonnet-4-6")
        )
        assertEquals(
            "gpt-5-mini",
            AgentModelConfig.openAiFallbackModelFor("deepseek/deepseek-v4-flash:free")
        )
    }

    @Test
    fun allowAnthropicChatFallbackFor_blocksExplicitDeepSeekChatModel() {
        assertEquals(
            false,
            AgentModelConfig.allowAnthropicChatFallbackFor("deepseek/deepseek-v4-flash:free")
        )
        assertEquals(
            true,
            AgentModelConfig.allowAnthropicChatFallbackFor("qwen/qwen3.7-max")
        )
    }

    @Test
    fun deepSeekChatFallbackFor_mapsFreeChatModelOnly() {
        assertEquals(
            "deepseek/deepseek-v4-flash",
            AgentModelConfig.deepSeekChatFallbackFor("deepseek/deepseek-v4-flash:free")
        )
        assertNull(
            AgentModelConfig.deepSeekChatFallbackFor("qwen/qwen3.7-max")
        )
    }

    @Test
    fun openAiWebSearchModelFor_prefersSearchCapableModel() {
        assertEquals(
            "gpt-5-search-api",
            AgentModelConfig.openAiWebSearchModelFor("gpt-5-mini")
        )
        assertEquals(
            "gpt-5-search-api",
            AgentModelConfig.openAiWebSearchModelFor("gpt-5-search-api")
        )
    }
}
