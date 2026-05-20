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
    const val TOOL_SPOTIFY_ADD_TO_PLAYLIST = "spotify_add_to_playlist"
    const val TOOL_SPOTIFY_GET_PLAYBACK_STATE = "spotify_get_playback_state"
    const val TOOL_SPOTIFY_CONTROL_PLAYBACK = "spotify_control_playback"
    const val TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS = "spotify_set_playback_options"
    const val TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS = "spotify_list_playlist_tracks"
    const val TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST = "spotify_remove_from_playlist"
    const val TOOL_SPOTIFY_UPDATE_PLAYLIST = "spotify_update_playlist"
    const val TOOL_SPOTIFY_REORDER_PLAYLIST = "spotify_reorder_playlist"
    const val TOOL_SPOTIFY_SEARCH = "spotify_search"
    const val TOOL_SPOTIFY_LIBRARY = "spotify_library"
    const val TOOL_SPOTIFY_TOP_ITEMS = "spotify_top_items"
    const val TOOL_SPOTIFY_ARTIST_TOP_TRACKS = "spotify_artist_top_tracks"
    const val TOOL_SEND_SMS = "send_sms"
    const val TOOL_SEND_WHATSAPP = "send_whatsapp_message"
    const val TOOL_START_NAVIGATION = "start_navigation"
    const val TOOL_SPOTIFY_CREATE_PLAYLIST = "spotify_create_playlist"
    const val TOOL_CHECK_EMAILS = "check_emails"
    const val TOOL_READ_EMAIL = "read_email"
    const val TOOL_CHECK_CALENDAR = "check_calendar"
    const val TOOL_LIST_APPS = "list_apps"
    const val TOOL_CLIPBOARD_GET = "clipboard_get"
    const val TOOL_CLIPBOARD_SET = "clipboard_set"
    const val TOOL_SEARCH_CONTACTS = "search_contacts"
    const val TOOL_SET_VOLUME = "set_volume"
    const val TOOL_MEDIA_CONTROL = "media_control"
    const val TOOL_TOGGLE_FLASHLIGHT = "toggle_flashlight"
    const val TOOL_GET_DEVICE_STATUS = "get_device_status"
    const val TOOL_GET_LOCATION = "get_location"
    const val TOOL_MAPS_TRAVEL_TIME = "maps_travel_time"
    const val TOOL_READ_NOTIFICATIONS = "read_notifications"
    private val sharedToolNames = setOf(
        TOOL_SEARCH_WEB,
        TOOL_CALL_CONTACT,
        TOOL_CLOCK_TIMER,
        TOOL_CLOCK_ALARM,
        TOOL_CLOCK_STOPWATCH,
        TOOL_SPOTIFY_PLAY_SONG,
        TOOL_SPOTIFY_PLAY_ALBUM,
        TOOL_SPOTIFY_PLAY_PLAYLIST,
        TOOL_SPOTIFY_LIST_PLAYLISTS,
        TOOL_SPOTIFY_ADD_TO_PLAYLIST,
        TOOL_SPOTIFY_GET_PLAYBACK_STATE,
        TOOL_SPOTIFY_CONTROL_PLAYBACK,
        TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS,
        TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS,
        TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST,
        TOOL_SPOTIFY_UPDATE_PLAYLIST,
        TOOL_SPOTIFY_REORDER_PLAYLIST,
        TOOL_SPOTIFY_SEARCH,
        TOOL_SPOTIFY_LIBRARY,
        TOOL_SPOTIFY_TOP_ITEMS,
        TOOL_SPOTIFY_ARTIST_TOP_TRACKS,
        TOOL_SEND_SMS,
        TOOL_SEND_WHATSAPP,
        TOOL_START_NAVIGATION,
        TOOL_SPOTIFY_CREATE_PLAYLIST,
        TOOL_CHECK_EMAILS,
        TOOL_READ_EMAIL,
        TOOL_CHECK_CALENDAR,
        TOOL_LIST_APPS,
        TOOL_CLIPBOARD_GET,
        TOOL_CLIPBOARD_SET,
        TOOL_SEARCH_CONTACTS,
        TOOL_SET_VOLUME,
        TOOL_MEDIA_CONTROL,
        TOOL_TOGGLE_FLASHLIGHT,
        TOOL_GET_DEVICE_STATUS,
        TOOL_GET_LOCATION,
        TOOL_MAPS_TRAVEL_TIME,
        TOOL_READ_NOTIFICATIONS
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
        description = "Start Spotify playback for one of the user's Spotify playlists. Search only within the user's Spotify playlists, or provide a direct Spotify playlist URI or URL. Optionally set shuffle as part of the same request.",
        properties = mapOf(
            "query" to stringToolProperty("Playlist name, or a direct Spotify playlist URI or URL."),
            "shuffle" to booleanToolProperty("Optional shuffle state to apply after playback starts.")
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

    private fun spotifyAddToPlaylistTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_ADD_TO_PLAYLIST,
        description = "Add explicitly requested songs to an existing Spotify playlist in the user's account, without opening Spotify. Use this for requests like 'add X by Y to my Road Trip playlist'. Never add random songs or filler tracks.",
        properties = mapOf(
            "playlist" to stringToolProperty("Existing playlist name, or a direct Spotify playlist URI or URL."),
            "track_queries" to arrayOfStringsToolProperty("Explicitly requested songs to add. Preserve title and artist when provided, for example 'Billie Jean by Michael Jackson'. Do not invent songs."),
            "song_uris" to arrayOfStringsToolProperty("Optional direct Spotify track URIs to add.")
        ),
        required = listOf("playlist", "track_queries")
    )

    private fun spotifyGetPlaybackStateTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_GET_PLAYBACK_STATE,
        description = "Read the current Spotify playback state without opening Spotify. Returns current track, artist, album, device, playing state, shuffle, repeat, progress, and volume.",
        properties = emptyMap()
    )

    private fun spotifyControlPlaybackTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_CONTROL_PLAYBACK,
        description = "Control Spotify playback without opening Spotify. Use for pause, resume, skip to next song, or go to previous song.",
        properties = mapOf(
            "action" to enumStringToolProperty(
                description = "Playback action to perform.",
                values = listOf("pause", "resume", "next", "previous")
            )
        ),
        required = listOf("action")
    )

    private fun spotifySetPlaybackOptionsTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS,
        description = "Set Spotify playback options without opening Spotify. You may set volume_percent, shuffle, repeat_mode, or a combination of them.",
        properties = mapOf(
            "volume_percent" to integerToolProperty("Optional volume level from 0 to 100."),
            "shuffle" to booleanToolProperty("Optional shuffle state."),
            "repeat_mode" to enumStringToolProperty(
                description = "Optional repeat mode.",
                values = listOf("off", "track", "context")
            )
        )
    )

    private fun spotifyListPlaylistTracksTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS,
        description = "List tracks in one of the user's owned or collaborative Spotify playlists without opening Spotify.",
        properties = mapOf(
            "playlist" to stringToolProperty("Existing playlist name, or a direct Spotify playlist URI or URL."),
            "limit" to integerToolProperty("Maximum number of tracks to return. Defaults to 25."),
            "offset" to integerToolProperty("Zero-based offset for pagination. Defaults to 0.")
        ),
        required = listOf("playlist")
    )

    private fun spotifyRemoveFromPlaylistTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST,
        description = "Remove explicitly requested tracks from an existing Spotify playlist without opening Spotify. Use this when the user has clearly asked to remove specific songs.",
        properties = mapOf(
            "playlist" to stringToolProperty("Existing playlist name, or a direct Spotify playlist URI or URL."),
            "track_queries" to arrayOfStringsToolProperty("Explicitly requested songs to remove. Preserve title and artist when provided."),
            "song_uris" to arrayOfStringsToolProperty("Optional direct Spotify track URIs to remove.")
        ),
        required = listOf("playlist")
    )

    private fun spotifyUpdatePlaylistTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_UPDATE_PLAYLIST,
        description = "Rename a Spotify playlist or update its description or public/private setting without opening Spotify. Use this only for changes the user explicitly requested.",
        properties = mapOf(
            "playlist" to stringToolProperty("Existing playlist name, or a direct Spotify playlist URI or URL."),
            "name" to stringToolProperty("Optional new playlist name."),
            "description" to stringToolProperty("Optional new playlist description. Use an empty string only when the user explicitly asks to clear it."),
            "public" to booleanToolProperty("Optional public/private setting.")
        ),
        required = listOf("playlist")
    )

    private fun spotifyReorderPlaylistTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_REORDER_PLAYLIST,
        description = "Move one track or a contiguous range within a Spotify playlist without opening Spotify. You may either provide zero-based indices directly, or identify the moved track and destination by song reference.",
        properties = mapOf(
            "playlist" to stringToolProperty("Existing playlist name, or a direct Spotify playlist URI or URL."),
            "range_start" to integerToolProperty("Optional zero-based index of the first track to move."),
            "insert_before" to integerToolProperty("Optional zero-based index where the range should be inserted before."),
            "range_length" to integerToolProperty("Number of contiguous tracks to move. Defaults to 1."),
            "track_query" to stringToolProperty("Optional specific song in the playlist to move, for example 'Billie Jean by Michael Jackson'. Use this when the user names the song instead of giving indices."),
            "song_uri" to stringToolProperty("Optional direct Spotify track URI for the song to move."),
            "before_track_query" to stringToolProperty("Optional specific song that the moved song should be inserted before."),
            "before_song_uri" to stringToolProperty("Optional direct Spotify track URI that the moved song should be inserted before."),
            "destination" to enumStringToolProperty(
                description = "Optional shortcut destination when the user says to move the song to the top or bottom.",
                values = listOf("top", "bottom")
            )
        ),
        required = listOf("playlist")
    )

    private fun spotifySearchTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_SEARCH,
        description = "Search Spotify tracks, albums, artists, or playlists and return structured candidates. Use this to disambiguate items before playing, saving, or editing.",
        properties = mapOf(
            "query" to stringToolProperty("Spotify search query."),
            "type" to enumStringToolProperty(
                description = "Type of item to search for.",
                values = listOf("track", "album", "artist", "playlist")
            ),
            "limit" to integerToolProperty("Maximum candidates to return. Defaults to 10.")
        ),
        required = listOf("query", "type")
    )

    private fun spotifyLibraryTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_LIBRARY,
        description = "Read or edit the user's Spotify library without opening Spotify. Supports listing saved tracks, saving explicit items, removing explicit items the user explicitly requested, and checking whether explicit items are saved.",
        properties = mapOf(
            "action" to enumStringToolProperty(
                description = "Library action to perform.",
                values = listOf("list_saved_tracks", "save_items", "remove_items", "contains_items")
            ),
            "item_type" to enumStringToolProperty(
                description = "Item type for save, remove, or contains. Defaults to track.",
                values = listOf("track", "album")
            ),
            "queries" to arrayOfStringsToolProperty("Optional explicit item queries to resolve, such as song title and artist."),
            "uris" to arrayOfStringsToolProperty("Optional direct Spotify item URIs or URLs."),
            "limit" to integerToolProperty("Maximum saved tracks to list. Defaults to 20."),
            "offset" to integerToolProperty("Zero-based offset for listing saved tracks. Defaults to 0.")
        ),
        required = listOf("action")
    )

    private fun spotifyTopItemsTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_TOP_ITEMS,
        description = "List the user's top Spotify tracks or artists without opening Spotify.",
        properties = mapOf(
            "type" to enumStringToolProperty(
                description = "Top item type.",
                values = listOf("tracks", "artists")
            ),
            "time_range" to enumStringToolProperty(
                description = "Time range. short_term is roughly the last month, medium_term roughly the last 6 months, and long_term several years.",
                values = listOf("short_term", "medium_term", "long_term")
            ),
            "limit" to integerToolProperty("Maximum items to return. Defaults to 10.")
        ),
        required = listOf("type")
    )

    private fun spotifyArtistTopTracksTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_ARTIST_TOP_TRACKS,
        description = "Get top tracks for a named Spotify artist without opening Spotify.",
        properties = mapOf(
            "artist" to stringToolProperty("Artist name, Spotify artist URI, or Spotify artist URL."),
            "market" to stringToolProperty("Optional market code. Defaults to from_token.")
        ),
        required = listOf("artist")
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
        description = "Send a WhatsApp message to a contact, a phone number, OR a group/chat by name (e.g. 'the family group chat'). This is the required first tool for explicit WhatsApp message requests, including individual contacts and groups. Contacts can complete through a direct deep link. Groups or chats not in contacts are selected in WhatsApp's share picker with the message pre-filled, then the tool may return needs_manual_final_send=true so the phone agent can read the screen and manually press the final Send/Next control. Use this only when the user explicitly requests WhatsApp; for general 'send a message' or 'text' requests prefer send_sms. Never open WhatsApp or use manual accessibility steps before trying this tool. Never tell the user to tap send; if it cannot complete it will say so.",
        properties = mapOf(
            "contact_name" to stringToolProperty("Contact name, group/chat name, or direct phone number to send the WhatsApp message to. A group or chat name (even phrased like 'the family group chat') is accepted."),
            "message" to stringToolProperty("The message to send.")
        ),
        required = listOf("contact_name", "message")
    )

    private fun startNavigationTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_START_NAVIGATION,
        description = "Start Google Maps turn-by-turn navigation to a destination, optionally via ordered intermediate stops, without opening or driving the Maps UI. Use this for any 'navigate to', 'directions to', 'direct me to', 'take me to', or 'route me to' request, including multi-stop trips described with words like 'via' or 'with a stop in'. Navigation starts from the user's current location.",
        properties = mapOf(
            "destination" to stringToolProperty("Final destination as an address, place name, or 'lat,lng'."),
            "waypoints" to arrayOfStringsToolProperty("Ordered intermediate stops to pass through, in travel order, before the destination."),
            "travel_mode" to enumStringToolProperty(
                description = "Mode of travel. Defaults to driving.",
                values = listOf("driving", "walking", "bicycling", "transit")
            ),
            "avoid" to enumStringToolProperty(
                description = "Optional route feature to avoid.",
                values = listOf("tolls", "highways", "ferries")
            )
        ),
        required = listOf("destination")
    )

    private fun spotifyCreatePlaylistTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SPOTIFY_CREATE_PLAYLIST,
        description = "Create a new Spotify playlist in the user's account and optionally add explicitly requested songs to it. Spotify must already be connected with playlist permissions. Use this for requests like 'make a playlist called X' or 'create a Spotify playlist with these songs'. Never add random songs or filler tracks.",
        properties = mapOf(
            "name" to stringToolProperty("Name for the new playlist."),
            "description" to stringToolProperty("Optional playlist description."),
            "public" to booleanToolProperty("Whether the playlist is public. Defaults to false (private)."),
            "track_queries" to arrayOfStringsToolProperty("Optional explicitly requested songs to add. Preserve the user's title and artist when provided, for example 'Billie Jean by Michael Jackson'. Do not invent songs."),
            "song_uris" to arrayOfStringsToolProperty("Optional direct Spotify track URIs to add.")
        ),
        required = listOf("name")
    )

    private fun checkEmailsTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_CHECK_EMAILS,
        description = "Search recent messages from the user's connected Gmail account so you can tell them about important or relevant email, without opening any app. Use this for requests like 'do I have any important emails today', 'any new email from my boss', or 'check my inbox'. Returns structured email metadata including message_id values; use read_email afterward when the user asks to read or inspect a specific result.",
        properties = mapOf(
            "query" to stringToolProperty("Optional Gmail search query. Defaults to important and recent mail. Examples: 'is:important newer_than:1d', 'from:boss@work.com newer_than:2d', 'is:unread'."),
            "max" to integerToolProperty("Maximum number of emails to return. Defaults to 10."),
            "since_hours" to integerToolProperty("Optional. Only include mail received within this many hours.")
        )
    )

    private fun readEmailTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_READ_EMAIL,
        description = "Read one Gmail message from the user's connected account after check_emails returns a message_id. Use this for follow-ups like 'read that security alert' or 'open the second email' by passing the matching message_id from the previous check_emails result. Returns structured headers, snippet, and readable body text for continued reasoning.",
        properties = mapOf(
            "message_id" to stringToolProperty("Gmail message_id returned by check_emails.")
        ),
        required = listOf("message_id")
    )

    private fun checkCalendarTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_CHECK_CALENDAR,
        description = "Read upcoming events from the user's connected Google Calendar so you can tell them their schedule, without opening any app. Use this for requests like 'what's on my calendar today', 'am I free this afternoon', 'what's my next meeting', or 'what's happening on a specific date'. Returns raw event data including each event's id and link for you to summarize; the Google account must already be connected.",
        properties = mapOf(
            "range" to enumStringToolProperty(
                description = "Relative time window to report. Defaults to today. Ignored if date or date_range is given.",
                values = listOf("today", "tomorrow", "next_24h")
            ),
            "date" to stringToolProperty("Optional specific calendar day as YYYY-MM-DD. Overrides range."),
            "date_range" to stringToolProperty("Optional explicit range as YYYY-MM-DD..YYYY-MM-DD (inclusive start day to exclusive end day). Overrides range and date.")
        )
    )

    private fun listAppsTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_LIST_APPS,
        description = "List installed launchable apps on the device, optionally filtered by a name substring. Use this to answer 'what apps do I have' or to check whether an app is installed before opening it.",
        properties = mapOf(
            "filter" to stringToolProperty("Optional case-insensitive substring to filter app labels or package names.")
        )
    )

    private fun clipboardGetTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_CLIPBOARD_GET,
        description = "Read the current clipboard text. Note Android 10+ blocks clipboard reads when the app is not focused; this returns a clear error in that case.",
        properties = emptyMap()
    )

    private fun clipboardSetTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_CLIPBOARD_SET,
        description = "Write text to the clipboard so the user can paste it elsewhere.",
        properties = mapOf(
            "text" to stringToolProperty("Text to place on the clipboard.")
        ),
        required = listOf("text")
    )

    private fun searchContactsTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SEARCH_CONTACTS,
        description = "Find people in the user's contacts by name or number substring. Returns phone numbers, emails, and which messaging apps (WhatsApp/Telegram/Signal) each contact has. Use this before send_whatsapp_message or call_contact, or to answer 'what is X's number' or 'is Y on WhatsApp'.",
        properties = mapOf(
            "query" to stringToolProperty("Name or number substring to search for."),
            "limit" to integerToolProperty("Maximum contacts to return. Defaults to 10."),
            "app" to stringToolProperty("Optional messaging app filter; only return contacts that have this app (whatsapp, telegram, signal).")
        ),
        required = listOf("query")
    )

    private fun setVolumeTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_SET_VOLUME,
        description = "Set a device audio stream's volume from 0 to 100, or mute/unmute it, without opening Settings.",
        properties = mapOf(
            "stream" to enumStringToolProperty(
                description = "Audio stream to change. Defaults to media.",
                values = listOf("media", "ring", "alarm", "notification", "call")
            ),
            "level" to integerToolProperty("Target volume from 0 to 100."),
            "mute" to booleanToolProperty("Optional. true mutes the stream, false unmutes it.")
        )
    )

    private fun mediaControlTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_MEDIA_CONTROL,
        description = "Control the device's active media session (any music/video app) by dispatching a media key. Use for generic 'pause the music', 'next track', 'stop' requests not tied to Spotify's API.",
        properties = mapOf(
            "action" to enumStringToolProperty(
                description = "Media action to perform.",
                values = listOf("play", "pause", "play_pause", "next", "previous", "stop")
            )
        ),
        required = listOf("action")
    )

    private fun toggleFlashlightTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_TOGGLE_FLASHLIGHT,
        description = "Turn the device torch/flashlight on, off, or toggle it.",
        properties = mapOf(
            "state" to enumStringToolProperty(
                description = "Desired torch state. Defaults to toggle.",
                values = listOf("on", "off", "toggle")
            )
        )
    )

    private fun getDeviceStatusTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_GET_DEVICE_STATUS,
        description = "Read device status: battery percent and charging state, connectivity (wifi/cellular/none), ringer mode, and screen brightness. Use for 'how's my battery' or 'am I on wifi'.",
        properties = emptyMap()
    )

    private fun getLocationTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_GET_LOCATION,
        description = "Get the user's current location coordinates without opening Maps. Use for requests like 'where am I', 'what is my location', or 'get my current location'. If location access or location services are off, this returns a structured error so the app can show the location settings button in chat.",
        properties = emptyMap()
    )

    private fun mapsTravelTimeTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_MAPS_TRAVEL_TIME,
        description = "Get travel time and distance in miles to a place WITHOUT opening Maps. Use for 'how long to get to X', 'how far is Y', 'how many miles to Z'. Origin defaults to the user's current location, so location access may need to be enabled first. By default this prefers precise traffic aware timing for live current-location requests. If the user explicitly agrees to an approximate fallback after a precise failure, set allow_approximate=true. This returns data only and never launches navigation; use start_navigation to actually navigate.",
        properties = mapOf(
            "destination" to stringToolProperty("Destination as an address or place name."),
            "origin" to stringToolProperty("Optional origin address or place name. Defaults to the user's current location."),
            "travel_mode" to enumStringToolProperty(
                description = "Mode of travel. Defaults to driving.",
                values = listOf("driving", "walking", "bicycling", "transit")
            ),
            "allow_approximate" to booleanToolProperty("Optional. Set true only when the user explicitly wants an approximate fallback instead of precise live traffic timing.")
        ),
        required = listOf("destination")
    )

    private fun readNotificationsTool(): FunctionToolSchema = FunctionToolSchema(
        name = TOOL_READ_NOTIFICATIONS,
        description = "Return recent notifications (Slack, WhatsApp, SMS, Gmail, etc.) buffered by the notification listener, without opening the notification shade. Use for 'any important Slack messages', 'what notifications do I have', or as part of a daily briefing. If notification access is not enabled this returns a structured error telling the user to enable it.",
        properties = mapOf(
            "app" to stringToolProperty("Optional case-insensitive substring to only include notifications from matching apps (e.g. 'slack')."),
            "since_minutes" to integerToolProperty("Optional. Only include notifications from the last this-many minutes."),
            "limit" to integerToolProperty("Maximum notifications to return. Defaults to 15."),
            "include_ongoing" to booleanToolProperty("Include ongoing/persistent notifications. Defaults to false.")
        )
    )

    fun agentFunctionTools(): List<FunctionToolSchema> {
        return listOf(
            searchWebTool(),
            callContactTool(),
            sendSmsTool(),
            sendWhatsAppTool(),
            startNavigationTool(),
            clockTimerTool(),
            clockAlarmTool(),
            clockStopwatchTool(),
            spotifyPlaySongTool(),
            spotifyPlayAlbumTool(),
            spotifyPlayPlaylistTool(),
            spotifyListPlaylistsTool(),
            spotifyAddToPlaylistTool(),
            spotifyGetPlaybackStateTool(),
            spotifyControlPlaybackTool(),
            spotifySetPlaybackOptionsTool(),
            spotifyListPlaylistTracksTool(),
            spotifyRemoveFromPlaylistTool(),
            spotifyUpdatePlaylistTool(),
            spotifyReorderPlaylistTool(),
            spotifySearchTool(),
            spotifyLibraryTool(),
            spotifyTopItemsTool(),
            spotifyArtistTopTracksTool(),
            spotifyCreatePlaylistTool(),
            checkEmailsTool(),
            readEmailTool(),
            checkCalendarTool(),
            listAppsTool(),
            clipboardGetTool(),
            clipboardSetTool(),
            searchContactsTool(),
            setVolumeTool(),
            mediaControlTool(),
            toggleFlashlightTool(),
            getDeviceStatusTool(),
            getLocationTool(),
            mapsTravelTimeTool(),
            readNotificationsTool()
        )
    }

    fun chatFunctionTools(): List<FunctionToolSchema> {
        return listOf(
            searchWebTool(),
            callContactTool(),
            sendSmsTool(),
            mapsTravelTimeTool(),
            spotifyPlaySongTool(),
            spotifyPlayAlbumTool(),
            spotifyGetPlaybackStateTool(),
            spotifyControlPlaybackTool(),
            spotifySetPlaybackOptionsTool(),
            listAppsTool(),
            clipboardGetTool(),
            clipboardSetTool(),
            getLocationTool(),
            readNotificationsTool()
        )
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

internal fun arrayOfStringsToolProperty(description: String): JSONObject {
    return JSONObject()
        .put("type", "array")
        .put("items", JSONObject().put("type", "string"))
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
