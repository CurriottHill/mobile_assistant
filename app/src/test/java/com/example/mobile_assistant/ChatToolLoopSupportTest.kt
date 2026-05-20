package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolLoopSupportTest {
    @Test
    fun appendSkippedToolResults_emitsToolResultForEveryRemainingId() {
        val toolCalls = JSONArray()
            .put(
                JSONObject()
                    .put("id", "call_1")
                    .put("function", JSONObject().put("name", "ask_user"))
            )
            .put(
                JSONObject()
                    .put("id", "call_2")
                    .put("function", JSONObject().put("name", "maps_travel_time"))
            )
            .put(
                JSONObject()
                    .put("id", "call_3")
                    .put("function", JSONObject().put("name", "search_web"))
            )
        val toolResults = JSONArray()

        ChatToolLoopSupport.appendSkippedToolResults(
            toolCalls = toolCalls,
            startIndex = 1,
            toolResults = toolResults,
            reason = "Skipped because ask_user already needs a reply."
        )

        assertEquals(2, toolResults.length())
        assertEquals("call_2", toolResults.getJSONObject(0).getString("tool_use_id"))
        assertEquals("call_3", toolResults.getJSONObject(1).getString("tool_use_id"))

        val firstContent = JSONObject(toolResults.getJSONObject(0).getString("content"))
        assertEquals("maps_travel_time", firstContent.getString("tool"))
        assertTrue(firstContent.getBoolean("skipped"))
        assertTrue(toolResults.getJSONObject(0).getBoolean("is_error"))
    }
}
