package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal data class FunctionToolSchema(
    val name: String,
    val description: String,
    val properties: Map<String, JSONObject> = emptyMap(),
    val required: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().also { props ->
                properties.forEach { (propertyName, schema) ->
                    props.put(propertyName, JSONObject(schema.toString()))
                }
            })
            .put("additionalProperties", false)

        if (required.isNotEmpty()) {
            parameters.put("required", JSONArray().also { items ->
                required.forEach(items::put)
            })
        }

        return JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)
    }

    fun propertyNames(): List<String> = properties.keys.toList()

    fun propertyDescription(name: String): String {
        return properties[name]?.optString("description").orEmpty()
    }

    fun isRequired(name: String): Boolean = name in required
}

internal object SharedToolSchemas {
    const val TOOL_SEARCH_WEB = "search_web"
    const val TOOL_CALL_CONTACT = "call_contact"
    const val TOOL_CLOCK_TIMER = "clock_timer"
    const val TOOL_CLOCK_ALARM = "clock_alarm"
    const val TOOL_CLOCK_STOPWATCH = "clock_stopwatch"
    const val TOOL_SPOTIFY_PLAY_SONG = "spotify_play_song"
    const val TOOL_SPOTIFY_PLAY_ALBUM = "spotify_play_album"
    const val TOOL_SPOTIFY_PLAY_PLAYLIST = "spotify_play_playlist"
    const val TOOL_SPOTIFY_LIST_PLAYLISTS = "spotify_list_playlists"
    const val TOOL_SEND_SMS = "send_sms"
    const val TOOL_SEND_WHATSAPP = "send_whatsapp_message"
    private val sharedToolNames = setOf(
        TOOL_SEARCH_WEB,
        TOOL_CALL_CONTACT,
        TOOL_CLOCK_TIMER,
        TOOL_CLOCK_ALARM,
        TOOL_SPOTIFY_PLAY_SONG,
        TOOL_SPOTIFY_PLAY_ALBUM,
        TOOL_SPOTIFY_PLAY_PLAYLIST,
        TOOL_SPOTIFY_LIST_PLAYLISTS,
        TOOL_SEND_SMS,
        TOOL_SEND_WHATSAPP
    )

    private fun searchWebTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SEARCH_WEB,
        description = "Search the web for information. Use this when you need up to date information to answer or complete a task, such as finding a URL, looking up a fact, or getting current data.",
        properties = mapOf(
            "query" to stringToolProperty("The search query.")
        ),
        required = listOf("query")
    )

    private fun callContactTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_CALL_CONTACT,
        description = "Start a phone call to a contact or phone number. Use this for requests like 'call mom' or 'call 5551234567'. It resolves the contact name to a phone number, then starts the call.",
        properties = mapOf(
            "contact_name" to stringToolProperty("Contact name or direct phone number to call.")
        ),
        required = listOf("contact_name")
    )

    private fun spotifyPlaySongTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_PLAY_SONG,
        description = "Start Spotify playback for a song. Search by song title, artist, or provide a direct Spotify track URI or URL. Spotify must already be connected in the app.",
        properties = mapOf(
            "query" to stringToolProperty("Song title, artist, or a direct Spotify track URI or URL.")
        ),
        required = listOf("query")
    )

    private fun spotifyPlayAlbumTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_PLAY_ALBUM,
        description = "Start Spotify playback for an album. Search by album title, artist, or provide a direct Spotify album URI or URL. Spotify must already be connected in the app.",
        properties = mapOf(
            "query" to stringToolProperty("Album title, artist, or a direct Spotify album URI or URL.")
        ),
        required = listOf("query")
    )

    private fun spotifyPlayPlaylistTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_PLAY_PLAYLIST,
        description = "Start Spotify playback for one of the user's Spotify playlists. Search only within the user's Spotify playlists, or provide a direct Spotify playlist URI or URL.",
        properties = mapOf(
            "query" to stringToolProperty("Playlist name, or a direct Spotify playlist URI or URL.")
        ),
        required = listOf("query")
    )

    private fun spotifyListPlaylistsTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_LIST_PLAYLISTS,
        description = "List the user's Spotify playlists, optionally filtered by name. Use this to answer questions about the user's playlists or to choose a playlist before playing it.",
        properties = mapOf(
            "query" to stringToolProperty("Optional playlist name filter."),
            "limit" to integerToolProperty("Maximum number of playlists to return. Defaults to 10.")
        )
    )

    private fun clockTimerTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_CLOCK_TIMER,
        description = "Control timers without manually opening the clock app. action=set starts a timer through the Android clock intent API. action=status reports the remaining time for the most recent tracked timer, or for a matching label if provided.",
        properties = mapOf(
            "action" to enumStringToolProperty(
                description = "Timer action to perform.",
                values = listOf("set", "status")
            ),
            "duration_seconds" to integerToolProperty("Required for action=set. Timer length in seconds, from 1 to 86400."),
            "label" to stringToolProperty("Optional timer label. Also used to look up a specific tracked timer for action=status.")
        ),
        required = listOf("action")
    )

    private fun clockAlarmTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_CLOCK_ALARM,
        description = "Control alarms without manually opening the clock app. action=set creates or enables an alarm through the Android clock intent API. action=status reports the next scheduled alarm. action=dismiss dismisses the next alarm by default, or a matching time or label when provided. action=snooze snoozes the currently ringing alarm.",
        properties = mapOf(
            "action" to enumStringToolProperty(
                description = "Alarm action to perform.",
                values = listOf("set", "status", "dismiss", "snooze")
            ),
            "hour" to integerToolProperty("Hour in 24 hour time, 0 to 23. Required for action=set. Optional for action=dismiss when targeting a specific alarm time."),
            "minute" to integerToolProperty("Minute, 0 to 59. Required for action=set. Optional for action=dismiss when targeting a specific alarm time."),
            "label" to stringToolProperty("Optional alarm label. For action=dismiss this is used to match an alarm by label."),
            "days" to stringToolProperty("Optional repeating weekdays for action=set, as a comma separated list such as 'monday,wednesday,friday'."),
            "vibrate" to booleanToolProperty("Optional vibration preference for action=set."),
            "dismiss_all" to booleanToolProperty("Optional for action=dismiss. When true, asks the clock app to dismiss all alarms."),
            "snooze_minutes" to integerToolProperty("Optional snooze length in minutes for action=snooze.")
        ),
        required = listOf("action")
    )

    private fun clockStopwatchTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_CLOCK_STOPWATCH,
        description = "Control the assistant managed stopwatch without opening a clock app. action=start starts it if needed. action=pause pauses it. action=resume resumes a paused stopwatch. action=reset clears it. action=status reports the current elapsed time.",
        properties = mapOf(
            "action" to enumStringToolProperty(
                description = "Stopwatch action to perform.",
                values = listOf("start", "pause", "resume", "reset", "status")
            ),
            "label" to stringToolProperty("Optional stopwatch label, mainly for action=start.")
        ),
        required = listOf("action")
    )

    private fun sendSmsTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SEND_SMS,
        description = "Send an SMS text message to a contact or phone number. Prefer this over send_whatsapp_message for any general 'send a message' or 'text' request unless the user explicitly asks for WhatsApp. Resolves the contact name to a phone number, then sends the message directly without opening any app.",
        properties = mapOf(
            "contact_name" to stringToolProperty("Contact name or direct phone number to send the message to."),
            "message" to stringToolProperty("The text message to send.")
        ),
        required = listOf("contact_name", "message")
    )

    private fun sendWhatsAppTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SEND_WHATSAPP,
        description = "Send a WhatsApp message to a contact or phone number. Use this only when the user explicitly requests WhatsApp. For general 'send a message' or 'text' requests, prefer send_sms instead.",
        properties = mapOf(
            "contact_name" to stringToolProperty("Contact name or direct phone number to send the WhatsApp message to."),
            "message" to stringToolProperty("The message to send.")
        ),
        required = listOf("contact_name", "message")
    )

    fun agentFunctionTools(): List<FunctionToolSchema> {
        return listOf(
            searchWebTool(),
            callContactTool(),
            sendSmsTool(),
            sendWhatsAppTool(),
            clockTimerTool(),
            clockAlarmTool(),
            spotifyPlaySongTool(),
            spotifyPlayAlbumTool(),
            spotifyPlayPlaylistTool(),
            spotifyListPlaylistsTool()
        )
    }

    fun chatFunctionTools(): List<FunctionToolSchema> {
        return agentFunctionTools()
    }

    fun isSharedTool(name: String): Boolean {
        return name in sharedToolNames
    }
}

internal fun stringToolProperty(description: String): JSONObject {
    return JSONObject()
        .put("type", "string")
        .put("description", description)
}

internal fun enumStringToolProperty(description: String, values: List<String>): JSONObject {
    return stringToolProperty(description).put(
        "enum",
        JSONArray().also { items -> values.forEach(items::put) }
    )
}

internal fun booleanToolProperty(description: String): JSONObject {
    return JSONObject()
        .put("type", "boolean")
        .put("description", description)
}

internal fun integerToolProperty(description: String): JSONObject {
    return JSONObject()
        .put("type", "integer")
        .put("description", description)
}

internal fun numberToolProperty(description: String): JSONObject {
    return JSONObject()
        .put("type", "number")
        .put("description", description)
}

internal fun findStringFunctionArgument(
    toolCalls: JSONArray?,
    toolName: String,
    argumentName: String
): String? {
    val args = findFunctionArguments(toolCalls, toolName) ?: return null
    return args.optString(argumentName, "")
}

internal fun findFunctionArguments(
    toolCalls: JSONArray?,
    toolName: String
): JSONObject? {
    if (toolCalls == null) return null
    for (i in 0 until toolCalls.length()) {
        val toolCall = toolCalls.optJSONObject(i) ?: continue
        val function = toolCall.optJSONObject("function") ?: continue
        if (function.optString("name") != toolName) continue
        return runCatching {
            JSONObject(function.optString("arguments", "{}"))
        }.getOrNull()
    }
    return null
}
