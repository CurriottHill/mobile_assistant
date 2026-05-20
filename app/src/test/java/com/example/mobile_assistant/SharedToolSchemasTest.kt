package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SharedToolSchemasTest {
    @Test
    fun spotifyTools_areMarkedAsSharedTools() {
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SEARCH_WEB))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_CALL_CONTACT))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_PLAY_ALBUM))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_PLAY_PLAYLIST))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLISTS))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_ADD_TO_PLAYLIST))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_GET_PLAYBACK_STATE))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_CONTROL_PLAYBACK))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_UPDATE_PLAYLIST))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_REORDER_PLAYLIST))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_SEARCH))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_LIBRARY))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_TOP_ITEMS))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_ARTIST_TOP_TRACKS))
    }

    @Test
    fun chatPrompt_exposesSharedChatToolsAndUsePhone() {
        val toolNames = anthropicToolNames(ChatPrompt.buildAnthropicTools())

        assertEquals(
            setOf(
                SharedToolSchemas.TOOL_SEARCH_WEB,
                SharedToolSchemas.TOOL_CALL_CONTACT,
                SharedToolSchemas.TOOL_SEND_SMS,
                SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME,
                SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG,
                SharedToolSchemas.TOOL_SPOTIFY_PLAY_ALBUM,
                SharedToolSchemas.TOOL_SPOTIFY_GET_PLAYBACK_STATE,
                SharedToolSchemas.TOOL_SPOTIFY_CONTROL_PLAYBACK,
                SharedToolSchemas.TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS,
                SharedToolSchemas.TOOL_LIST_APPS,
                SharedToolSchemas.TOOL_CLIPBOARD_GET,
                SharedToolSchemas.TOOL_CLIPBOARD_SET,
                SharedToolSchemas.TOOL_GET_LOCATION,
                SharedToolSchemas.TOOL_READ_NOTIFICATIONS,
                ChatPrompt.TOOL_OPEN_APP,
                ChatPrompt.TOOL_OPEN_NOTIFICATIONS,
                ChatPrompt.TOOL_ASK_USER,
                ChatPrompt.TOOL_USE_PHONE
            ),
            toolNames
        )
        assertFalse(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_LIBRARY))
        assertFalse(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST))
        assertFalse(toolNames.contains("spotify_delete_playlist"))
    }

    @Test
    fun chatPrompt_routesStructuredRequestsThroughUsePhone() {
        val instructions = ChatPrompt.instructions()

        assertTrue(instructions.contains("Phone time zone:"))
        assertTrue(instructions.contains("Use this time zone for times and scheduling"))
        assertTrue(instructions.contains("call use_phone"))
        assertTrue(instructions.contains("call openapp directly"))
        assertTrue(instructions.contains("call open_notifications directly"))
        assertTrue(instructions.contains("send_sms"))
        assertTrue(instructions.contains("call_contact"))
        assertTrue(instructions.contains("call get_location directly"))
        assertTrue(instructions.contains("maps_travel_time directly"))
        assertTrue(instructions.contains("allow_approximate=true"))
        assertTrue(instructions.contains("spotify_control_playback"))
        assertTrue(instructions.contains("spotify_play_album"))
        assertTrue(instructions.contains("read_notifications"))
        assertTrue(instructions.contains("Maps navigation"))
        assertTrue(instructions.contains("Spotify playlist creation or editing"))
        assertTrue(instructions.contains("email lookup or reading"))
        assertTrue(instructions.contains("calendar lookup"))
        assertTrue(instructions.contains("timers, alarms"))
        assertTrue(instructions.contains("Chat mode may use direct tools in a short loop."))
    }

    @Test
    fun agentPrompt_doesNotRequireSpotifyReconfirmation() {
        val instructions = AgentTooling.systemPrompt("remove one song from my playlist")

        assertTrue(instructions.contains("Phone time zone:"))
        assertTrue(instructions.contains("Do not ask_user just to reconfirm a clear Spotify request."))
        assertTrue(instructions.contains("including Spotify playlist or library edits"))
        assertFalse(instructions.contains("Before destructive playlist or library edits such as remove, reorder, rename, privacy changes, or unsave, ask_user for confirmation and wait for yes."))
    }

    @Test
    fun agentToolDefinitions_includeStructuredSharedTools() {
        val toolNames = AgentTooling.toolNames().toSet()

        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SEARCH_WEB))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_CALL_CONTACT))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SEND_SMS))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SEND_WHATSAPP))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_START_NAVIGATION))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_CLOCK_TIMER))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_CLOCK_ALARM))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_CLOCK_STOPWATCH))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_ADD_TO_PLAYLIST))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_CREATE_PLAYLIST))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_GET_PLAYBACK_STATE))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_CONTROL_PLAYBACK))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_UPDATE_PLAYLIST))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_REORDER_PLAYLIST))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_SEARCH))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_LIBRARY))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_TOP_ITEMS))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_SPOTIFY_ARTIST_TOP_TRACKS))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_CHECK_EMAILS))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_READ_EMAIL))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_CHECK_CALENDAR))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_GET_LOCATION))
        assertTrue(toolNames.contains(SharedToolSchemas.TOOL_READ_NOTIFICATIONS))
    }

    @Test
    fun mapsTravelTimeTool_defaultsToCurrentLocation() {
        val tool = findToolDefinition(SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME)
        val parameters = tool.getJSONObject("parameters").getJSONObject("properties")

        assertTrue(tool.getString("description").contains("current location"))
        assertTrue(tool.getString("description").contains("allow_approximate"))
        assertTrue(
            parameters.getJSONObject("origin").getString("description").contains("current location")
        )
        assertTrue(parameters.has("allow_approximate"))
    }

    @Test
    fun composeEmail_supportsDraftFirstThenMinimalSend() {
        val composeTool = findToolDefinition(AgentTooling.TOOL_COMPOSE_EMAIL)
        val parameters = composeTool.getJSONObject("parameters")
        val required = parameters.optJSONArray("required")
        val instructions = AgentTooling.systemPrompt("draft an email")

        assertTrue(composeTool.getString("description").contains("return the drafted subject and body"))
        assertTrue(required == null || !(0 until required.length()).any { required.optString(it) == "to" })
        assertTrue(instructions.contains("call compose_email with confirm_send=false"))
        assertTrue(instructions.contains("call compose_email with confirm_send=true"))
        assertTrue(instructions.contains("omit the draft fields"))
    }

    @Test
    fun calendarTools_supportDraftEditAndDeleteReviewFlows() {
        val createTool = findToolDefinition(AgentTooling.TOOL_CALENDAR_CREATE_EVENT)
        val editTool = findToolDefinition(AgentTooling.TOOL_CALENDAR_EDIT_EVENT)
        val deleteTool = findToolDefinition(AgentTooling.TOOL_CALENDAR_DELETE_EVENT)
        val createProperties = createTool.getJSONObject("parameters").getJSONObject("properties")
        val instructions = AgentTooling.systemPrompt("update my calendar")

        assertTrue(createTool.getString("description").contains("return the drafted event details"))
        assertFalse(createProperties.has("confirm_save"))
        assertTrue(editTool.getString("description").contains("Prefer event_link from check_calendar"))
        assertTrue(deleteTool.getString("description").contains("never presses Delete"))
        assertTrue(instructions.contains("calendar_create_event returns the drafted event details"))
        assertTrue(instructions.contains("call calendar_edit_event or calendar_delete_event"))
        assertTrue(instructions.contains("use check_calendar to identify the target event"))
    }

    @Test
    fun gmailBodyExtractor_decodesPlainTextParts() {
        val payload = org.json.JSONObject(
            """
            {
              "mimeType": "multipart/alternative",
              "parts": [
                {
                  "mimeType": "text/plain",
                  "body": {
                    "data": "SGVsbG8gZnJvbSBHbWFpbA"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("Hello from Gmail", GmailBodyTextExtractor.extract(payload))
    }

    private fun anthropicToolNames(tools: org.json.JSONArray): Set<String> {
        return (0 until tools.length())
            .mapNotNull { tools.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            .toSet()
    }

    private fun findToolDefinition(name: String): JSONObject {
        val tools = AgentTooling.buildToolDefinitions()
        return (0 until tools.length())
            .mapNotNull { tools.optJSONObject(it) }
            .first { it.optString("name") == name }
    }
}
