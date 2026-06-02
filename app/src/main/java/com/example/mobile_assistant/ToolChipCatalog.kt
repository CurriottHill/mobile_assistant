package com.example.mobile_assistant

import org.json.JSONObject

internal data class ToolChip(val iconRes: Int, val tintRes: Int, val label: String)

/**
 * Maps a tool call to the friendly icon + label chip shown in the chat card.
 * Tools that are not in [entries] (low-level screen mechanics, speak/ask_user/
 * task_complete/use_phone) intentionally produce no chip.
 */
internal object ToolChipCatalog {

    private const val MAX_TARGET = 22

    private class Entry(val iconRes: Int, val label: (JSONObject) -> String)

    private fun JSONObject.arg(vararg keys: String): String =
        keys.asSequence().map { optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun truncate(value: String): String =
        if (value.length <= MAX_TARGET) value else value.take(MAX_TARGET - 1).trimEnd() + "…"

    /** "prefix target", or [fallback] when none of [keys] hold a value. */
    private fun target(prefix: String, fallback: String, vararg keys: String): (JSONObject) -> String =
        { args ->
            val value = args.arg(*keys)
            if (value.isBlank()) fallback else "$prefix ${truncate(value)}"
        }

    private fun fixed(text: String): (JSONObject) -> String = { text }

    /** Label chosen from the "action" argument, falling back to [fallback]. */
    private fun byAction(fallback: String, mapping: Map<String, String>): (JSONObject) -> String =
        { args -> mapping[args.optString("action").trim().lowercase()] ?: fallback }

    private val entries: Map<String, Entry> = buildMap {
        fun put(name: String, iconRes: Int, label: (JSONObject) -> String) =
            put(name, Entry(iconRes, label))

        // Apps
        put(AgentTooling.TOOL_OPEN_APP, R.drawable.ic_tool_app, target("open", "open app", "name"))
        put(AgentTooling.TOOL_OPEN_URL, R.drawable.ic_tool_app, fixed("open link"))
        put(AgentTooling.TOOL_CLOSE_APP, R.drawable.ic_tool_app, fixed("close app"))
        put(AgentTooling.TOOL_LIST_APPS, R.drawable.ic_tool_app, fixed("list apps"))

        // Maps & location
        put(AgentTooling.TOOL_START_NAVIGATION, R.drawable.ic_tool_maps, target("navigate to", "start navigation", "destination"))
        put(AgentTooling.TOOL_MAPS_TRAVEL_TIME, R.drawable.ic_tool_maps, target("travel time to", "travel time", "destination"))
        put(AgentTooling.TOOL_GET_LOCATION, R.drawable.ic_tool_maps, fixed("get location"))

        // Messaging
        put(AgentTooling.TOOL_SEND_WHATSAPP, R.drawable.ic_tool_message, target("whatsapp to", "send whatsapp message", "contact_name"))
        put(AgentTooling.TOOL_SEND_SMS, R.drawable.ic_tool_message, target("text", "send text", "contact_name"))
        put(AgentTooling.TOOL_SEND_MESSAGE, R.drawable.ic_tool_message, target("message", "send message", "target"))

        // Calling & contacts
        put(AgentTooling.TOOL_CALL_CONTACT, R.drawable.ic_tool_call, target("call", "make call", "contact_name"))
        put(AgentTooling.TOOL_SEARCH_CONTACTS, R.drawable.ic_tool_contact, target("find contact", "find contact", "query"))

        // Spotify / music
        put(AgentTooling.TOOL_SPOTIFY_PLAY_SONG, R.drawable.ic_tool_music, target("play", "play song", "query"))
        put(AgentTooling.TOOL_SPOTIFY_PLAY_ALBUM, R.drawable.ic_tool_music, target("play album", "play album", "query"))
        put(AgentTooling.TOOL_SPOTIFY_PLAY_PLAYLIST, R.drawable.ic_tool_music, target("play playlist", "play playlist", "query"))
        put(AgentTooling.TOOL_SPOTIFY_CREATE_PLAYLIST, R.drawable.ic_tool_music, target("create playlist", "create playlist", "name"))
        put(AgentTooling.TOOL_SPOTIFY_ADD_TO_PLAYLIST, R.drawable.ic_tool_music, target("add to", "add to playlist", "playlist"))
        put(AgentTooling.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST, R.drawable.ic_tool_music, target("remove from", "remove from playlist", "playlist"))
        put(AgentTooling.TOOL_SPOTIFY_UPDATE_PLAYLIST, R.drawable.ic_tool_music, target("update", "update playlist", "playlist"))
        put(AgentTooling.TOOL_SPOTIFY_REORDER_PLAYLIST, R.drawable.ic_tool_music, target("reorder", "reorder playlist", "playlist"))
        put(AgentTooling.TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS, R.drawable.ic_tool_music, target("playlist", "playlist tracks", "playlist"))
        put(AgentTooling.TOOL_SPOTIFY_ARTIST_TOP_TRACKS, R.drawable.ic_tool_music, target("top tracks", "artist top tracks", "artist"))
        put(AgentTooling.TOOL_SPOTIFY_LIST_PLAYLISTS, R.drawable.ic_tool_music, fixed("spotify playlists"))
        put(AgentTooling.TOOL_SPOTIFY_GET_PLAYBACK_STATE, R.drawable.ic_tool_music, fixed("now playing"))
        put(AgentTooling.TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS, R.drawable.ic_tool_music, fixed("spotify settings"))
        put(AgentTooling.TOOL_SPOTIFY_SEARCH, R.drawable.ic_tool_music, fixed("spotify search"))
        put(AgentTooling.TOOL_SPOTIFY_LIBRARY, R.drawable.ic_tool_music, fixed("spotify library"))
        put(AgentTooling.TOOL_SPOTIFY_TOP_ITEMS, R.drawable.ic_tool_music, fixed("spotify top items"))
        put(
            AgentTooling.TOOL_SPOTIFY_CONTROL_PLAYBACK, R.drawable.ic_tool_music,
            byAction(
                "control music",
                mapOf(
                    "pause" to "pause music",
                    "resume" to "resume music",
                    "next" to "skip track",
                    "previous" to "previous track"
                )
            )
        )

        // Clock
        put(
            AgentTooling.TOOL_CLOCK_TIMER, R.drawable.ic_tool_clock,
            byAction("timer", mapOf("set" to "set timer", "status" to "timer status"))
        )
        put(
            AgentTooling.TOOL_CLOCK_ALARM, R.drawable.ic_tool_clock,
            byAction(
                "alarm",
                mapOf(
                    "set" to "set alarm",
                    "status" to "alarm status",
                    "dismiss" to "dismiss alarm",
                    "snooze" to "snooze alarm"
                )
            )
        )
        put(
            AgentTooling.TOOL_CLOCK_STOPWATCH, R.drawable.ic_tool_clock,
            byAction(
                "stopwatch",
                mapOf(
                    "start" to "start stopwatch",
                    "pause" to "pause stopwatch",
                    "resume" to "resume stopwatch",
                    "reset" to "reset stopwatch",
                    "status" to "stopwatch status"
                )
            )
        )

        // Email
        put(AgentTooling.TOOL_CHECK_EMAILS, R.drawable.ic_tool_email, fixed("check email"))
        put(AgentTooling.TOOL_READ_EMAIL, R.drawable.ic_tool_email, fixed("read email"))
        put(AgentTooling.TOOL_COMPOSE_EMAIL, R.drawable.ic_tool_email, target("email", "compose email", "subject"))

        // Calendar
        put(AgentTooling.TOOL_CHECK_CALENDAR, R.drawable.ic_tool_calendar, fixed("check calendar"))
        put(AgentTooling.TOOL_CALENDAR_CREATE_EVENT, R.drawable.ic_tool_calendar, target("add event", "add event", "title"))
        put(AgentTooling.TOOL_CALENDAR_EDIT_EVENT, R.drawable.ic_tool_calendar, target("edit event", "edit event", "title"))
        put(AgentTooling.TOOL_CALENDAR_DELETE_EVENT, R.drawable.ic_tool_calendar, fixed("delete event"))

        // Web
        put(AgentTooling.TOOL_SEARCH_WEB, R.drawable.ic_tool_web, target("search", "search web", "query"))

        // Weather
        put(
            AgentTooling.TOOL_GET_WEATHER, R.drawable.ic_tool_weather,
            { args ->
                val location = args.optString("location").trim()
                if (location.isBlank() || location.equals("here", ignoreCase = true)) "weather"
                else "weather in ${truncate(location)}"
            }
        )

        // Memory
        put(AgentTooling.TOOL_MEMORY_READ, R.drawable.ic_tool_memory, fixed("recall memory"))
        put(AgentTooling.TOOL_MEMORY_LIST, R.drawable.ic_tool_memory, fixed("recall memory"))
        put(AgentTooling.TOOL_MEMORY_EDIT, R.drawable.ic_tool_memory, fixed("save memory"))
        put(AgentTooling.TOOL_MEMORY_LINK, R.drawable.ic_tool_memory, fixed("link memory"))

        // Device
        put(AgentTooling.TOOL_GET_DEVICE_STATUS, R.drawable.ic_tool_device, fixed("device status"))
        put(AgentTooling.TOOL_SET_VOLUME, R.drawable.ic_tool_device, fixed("set volume"))
        put(AgentTooling.TOOL_TOGGLE_FLASHLIGHT, R.drawable.ic_tool_device, fixed("flashlight"))
        put(
            AgentTooling.TOOL_MEDIA_CONTROL, R.drawable.ic_tool_device,
            byAction(
                "media control",
                mapOf(
                    "play" to "play media",
                    "pause" to "pause media",
                    "play_pause" to "play / pause",
                    "next" to "next track",
                    "previous" to "previous track",
                    "stop" to "stop media"
                )
            )
        )

        // Clipboard
        put(AgentTooling.TOOL_CLIPBOARD_GET, R.drawable.ic_tool_clipboard, fixed("read clipboard"))
        put(AgentTooling.TOOL_CLIPBOARD_SET, R.drawable.ic_tool_clipboard, fixed("copy to clipboard"))

        // Notifications
        put(AgentTooling.TOOL_READ_NOTIFICATIONS, R.drawable.ic_tool_bell, fixed("notifications"))
    }

    /** Accent colour per category icon, used to tint the chip icon and its tile. */
    private val iconColors: Map<Int, Int> = mapOf(
        R.drawable.ic_tool_app to R.color.tool_icon_app,
        R.drawable.ic_tool_maps to R.color.tool_icon_maps,
        R.drawable.ic_tool_message to R.color.tool_icon_message,
        R.drawable.ic_tool_call to R.color.tool_icon_call,
        R.drawable.ic_tool_contact to R.color.tool_icon_contact,
        R.drawable.ic_tool_music to R.color.tool_icon_music,
        R.drawable.ic_tool_clock to R.color.tool_icon_clock,
        R.drawable.ic_tool_email to R.color.tool_icon_email,
        R.drawable.ic_tool_calendar to R.color.tool_icon_calendar,
        R.drawable.ic_tool_web to R.color.tool_icon_web,
        R.drawable.ic_tool_weather to R.color.tool_icon_weather,
        R.drawable.ic_tool_memory to R.color.tool_icon_memory,
        R.drawable.ic_tool_device to R.color.tool_icon_device,
        R.drawable.ic_tool_clipboard to R.color.tool_icon_clipboard,
        R.drawable.ic_tool_bell to R.color.tool_icon_bell
    )

    /**
     * Returns the chip for a tool call, or null when the tool should not be shown
     * in the chat (mechanics, speak/ask_user/task_complete/use_phone).
     */
    fun chipFor(toolName: String, argsJson: String): ToolChip? {
        val canonical = AgentTooling.canonicalToolName(toolName)
        val entry = entries[canonical] ?: return null
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return ToolChip(
            iconRes = entry.iconRes,
            tintRes = iconColors[entry.iconRes] ?: R.color.assistant_code_text,
            label = entry.label(args)
        )
    }
}
