package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal class SharedToolExecutor(
    private val searchWeb: suspend (String) -> SearchWebResult,
    private val callToolService: CallToolService,
    private val spotifyService: SpotifyService,
    private val clockToolService: ClockToolService,
    private val smsToolService: SmsToolService,
    private val whatsAppToolService: WhatsAppToolService,
    private val mapsToolService: MapsToolService,
    private val googleAccountService: GoogleAccountService,
    private val phoneUtilToolService: PhoneUtilToolService,
    private val contactsToolService: ContactsToolService,
    private val deviceMediaToolService: DeviceMediaToolService,
    private val currentLocationToolService: CurrentLocationToolService,
    private val mapsTravelTimeToolService: MapsTravelTimeToolService,
    private val notificationToolService: NotificationToolService
) {
    suspend fun execute(toolName: String, arguments: JSONObject): SharedToolExecutionResult? {
        return when (toolName) {
            SharedToolSchemas.TOOL_SEARCH_WEB -> executeSearchWeb(arguments)
            SharedToolSchemas.TOOL_CALL_CONTACT -> callToolService.executeCall(arguments)
            SharedToolSchemas.TOOL_SEND_SMS -> smsToolService.executeSendSms(arguments)
            SharedToolSchemas.TOOL_SEND_WHATSAPP -> whatsAppToolService.executeSend(arguments)
            SharedToolSchemas.TOOL_START_NAVIGATION -> mapsToolService.executeStartNavigation(arguments)
            SharedToolSchemas.TOOL_CLOCK_TIMER -> clockToolService.executeTimer(arguments)
            SharedToolSchemas.TOOL_CLOCK_ALARM -> clockToolService.executeAlarm(arguments)
            SharedToolSchemas.TOOL_CLOCK_STOPWATCH -> clockToolService.executeStopwatch(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG -> executeSpotifyPlaySong(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_PLAY_ALBUM -> executeSpotifyPlayAlbum(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_PLAY_PLAYLIST -> executeSpotifyPlayPlaylist(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLISTS -> executeSpotifyListPlaylists(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_ADD_TO_PLAYLIST -> executeSpotifyAddToPlaylist(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_CREATE_PLAYLIST -> executeSpotifyCreatePlaylist(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_GET_PLAYBACK_STATE -> executeSpotifyGetPlaybackState()
            SharedToolSchemas.TOOL_SPOTIFY_CONTROL_PLAYBACK -> executeSpotifyControlPlayback(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS -> executeSpotifySetPlaybackOptions(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS -> executeSpotifyListPlaylistTracks(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST -> executeSpotifyRemoveFromPlaylist(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_UPDATE_PLAYLIST -> executeSpotifyUpdatePlaylist(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_REORDER_PLAYLIST -> executeSpotifyReorderPlaylist(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_SEARCH -> executeSpotifySearch(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_LIBRARY -> executeSpotifyLibrary(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_TOP_ITEMS -> executeSpotifyTopItems(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_ARTIST_TOP_TRACKS -> executeSpotifyArtistTopTracks(arguments)
            SharedToolSchemas.TOOL_CHECK_EMAILS -> executeCheckEmails(arguments)
            SharedToolSchemas.TOOL_READ_EMAIL -> executeReadEmail(arguments)
            SharedToolSchemas.TOOL_CHECK_CALENDAR -> executeCheckCalendar(arguments)
            SharedToolSchemas.TOOL_LIST_APPS -> phoneUtilToolService.executeListApps(arguments)
            SharedToolSchemas.TOOL_CLIPBOARD_GET -> phoneUtilToolService.executeClipboardGet()
            SharedToolSchemas.TOOL_CLIPBOARD_SET -> phoneUtilToolService.executeClipboardSet(arguments)
            SharedToolSchemas.TOOL_SEARCH_CONTACTS -> contactsToolService.execute(arguments)
            SharedToolSchemas.TOOL_SET_VOLUME -> deviceMediaToolService.executeSetVolume(arguments)
            SharedToolSchemas.TOOL_MEDIA_CONTROL -> deviceMediaToolService.executeMediaControl(arguments)
            SharedToolSchemas.TOOL_TOGGLE_FLASHLIGHT -> deviceMediaToolService.executeToggleFlashlight(arguments)
            SharedToolSchemas.TOOL_GET_DEVICE_STATUS -> deviceMediaToolService.executeGetDeviceStatus()
            SharedToolSchemas.TOOL_GET_LOCATION -> currentLocationToolService.execute(arguments)
            SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME -> mapsTravelTimeToolService.execute(arguments)
            SharedToolSchemas.TOOL_READ_NOTIFICATIONS -> notificationToolService.execute(arguments)
            else -> null
        }
    }

    suspend fun executeFirstMatching(
        toolCalls: JSONArray?,
        allowedToolNames: Set<String>? = null
    ): SharedToolExecutionResult? {
        if (toolCalls == null) return null
        for (index in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(index) ?: continue
            val function = toolCall.optJSONObject("function") ?: continue
            val toolName = function.optString("name").trim()
            if (allowedToolNames != null && toolName !in allowedToolNames) continue
            val arguments = runCatching {
                JSONObject(function.optString("arguments", "{}"))
            }.getOrDefault(JSONObject())
            execute(toolName, arguments)?.let { return it }
        }
        return null
    }

    private suspend fun executeSearchWeb(arguments: JSONObject): SharedToolExecutionResult {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_SEARCH_WEB,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_SEARCH_WEB)
                    .put("error", "Missing query."),
                chatResponse = "I need a search query first."
            )
        }

        val searchResult = searchWeb(query)
        val content = JSONObject()
            .put("ok", searchResult.ok)
            .put("tool", SharedToolSchemas.TOOL_SEARCH_WEB)
            .put("query", query)

        if (searchResult.answer != null) {
            content.put("answer", searchResult.answer)
        }
        if (searchResult.error != null) {
            content.put("error", searchResult.error)
        }

        val chatResponse = when {
            !searchResult.answer.isNullOrBlank() -> searchResult.answer
            !searchResult.error.isNullOrBlank() -> "I could not search the web just now. ${searchResult.error}"
            else -> "I could not find anything useful just now."
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SEARCH_WEB,
            content = content,
            chatResponse = chatResponse
        )
    }

    private suspend fun executeSpotifyPlaySong(arguments: JSONObject): SharedToolExecutionResult {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG, "song")
        }

        val playback = spotifyService.playSong(query)
        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG,
            content = buildSpotifyPlaybackContent(
                toolName = SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG,
                playback = playback
            ),
            chatResponse = buildSpotifyPlaybackMessage(playback)
        )
    }

    private suspend fun executeSpotifyPlayAlbum(arguments: JSONObject): SharedToolExecutionResult {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_PLAY_ALBUM, "album")
        }

        val playback = spotifyService.playAlbum(query)
        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_PLAY_ALBUM,
            content = buildSpotifyPlaybackContent(
                toolName = SharedToolSchemas.TOOL_SPOTIFY_PLAY_ALBUM,
                playback = playback
            ),
            chatResponse = buildSpotifyPlaybackMessage(playback)
        )
    }

    private suspend fun executeSpotifyPlayPlaylist(arguments: JSONObject): SharedToolExecutionResult {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_PLAY_PLAYLIST, "playlist")
        }

        val shuffle = if (arguments.has("shuffle")) arguments.optBoolean("shuffle") else null
        val playback = spotifyService.playPlaylist(query, shuffle)
        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_PLAY_PLAYLIST,
            content = buildSpotifyPlaybackContent(
                toolName = SharedToolSchemas.TOOL_SPOTIFY_PLAY_PLAYLIST,
                playback = playback
            ),
            chatResponse = buildSpotifyPlaybackMessage(playback)
        )
    }

    private suspend fun executeSpotifyListPlaylists(arguments: JSONObject): SharedToolExecutionResult {
        val query = arguments.optString("query").trim().ifBlank { null }
        val limit = arguments.optInt("limit", 10)
        val playlists = spotifyService.listPlaylists(query, limit)
        val content = JSONObject()
            .put("ok", playlists.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLISTS)
            .put("limit", limit.coerceIn(1, 20))

        if (!query.isNullOrBlank()) {
            content.put("query", query)
        }
        if (playlists.error != null) {
            content.put("error", playlists.error)
        }

        content.put("playlists", JSONArray().also { items ->
            playlists.playlists.forEach { playlist ->
                items.put(
                    JSONObject()
                        .put("name", playlist.name)
                        .put("uri", playlist.uri)
                        .put("owner_name", playlist.ownerName)
                        .put("track_count", playlist.trackCount)
                )
            }
        })

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLISTS,
            content = content,
            chatResponse = buildSpotifyPlaylistMessage(playlists)
        )
    }

    private suspend fun executeSpotifyCreatePlaylist(arguments: JSONObject): SharedToolExecutionResult {
        val name = arguments.optString("name").trim()
        if (name.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_CREATE_PLAYLIST, "playlist")
        }
        val description = arguments.optString("description").trim().ifBlank { null }
        val isPublic = arguments.optBoolean("public", false)
        val trackQueries = readStringList(arguments, "track_queries")
        val songUris = readStringList(arguments, "song_uris")

        val result = spotifyService.createPlaylist(
            name = name,
            description = description,
            isPublic = isPublic,
            trackQueries = trackQueries,
            songUris = songUris
        )

        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_CREATE_PLAYLIST)
            .put("name", result.name)
            .put("added_count", result.addedCount)
            .also { c ->
                result.playlistUri?.let { c.put("playlist_uri", it) }
                if (result.notFoundQueries.isNotEmpty()) {
                    c.put("not_found", JSONArray().also { a -> result.notFoundQueries.forEach(a::put) })
                }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect && result.playlistUri != null ->
                "Spotify created the playlist, but needs playlist modification permission before I can add songs. Reconnect Spotify and try again."
            result.needsReconnect ->
                "Spotify needs playlist modification permission before I can create that playlist. Reconnect Spotify and try again."
            !result.ok ->
                result.error ?: "I could not create that Spotify playlist."
            result.addedCount > 0 -> buildString {
                append("Created the Spotify playlist ")
                append(result.name)
                append(" with ")
                append(result.addedCount)
                append(if (result.addedCount == 1) " song." else " songs.")
                if (result.notFoundQueries.isNotEmpty()) {
                    append(" I could not find ")
                    append(result.notFoundQueries.size)
                    append(if (result.notFoundQueries.size == 1) " song." else " songs.")
                }
            }
            else -> "Created the Spotify playlist ${result.name}."
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_CREATE_PLAYLIST,
            content = content,
            chatResponse = chatResponse,
            uiAction = if (result.needsReconnect) {
                AssistantUiAction(
                    type = AssistantUiActionType.CONNECT_SPOTIFY,
                    label = "Reconnect Spotify"
                )
            } else {
                null
            }
        )
    }

    private suspend fun executeSpotifyAddToPlaylist(arguments: JSONObject): SharedToolExecutionResult {
        val playlist = arguments.optString("playlist").trim()
        if (playlist.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_ADD_TO_PLAYLIST, "playlist")
        }
        val trackQueries = readStringList(arguments, "track_queries")
        val songUris = readStringList(arguments, "song_uris")
        if (trackQueries.isEmpty() && songUris.isEmpty()) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_SPOTIFY_ADD_TO_PLAYLIST,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_SPOTIFY_ADD_TO_PLAYLIST)
                    .put("error", "Missing songs to add."),
                chatResponse = "I need the songs to add first."
            )
        }

        val result = spotifyService.addSongsToPlaylist(
            playlistQuery = playlist,
            trackQueries = trackQueries,
            songUris = songUris
        )

        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_ADD_TO_PLAYLIST)
            .put("playlist", result.playlistName ?: playlist)
            .put("added_count", result.addedCount)
            .also { c ->
                result.playlistUri?.let { c.put("playlist_uri", it) }
                if (result.notFoundQueries.isNotEmpty()) {
                    c.put("not_found", JSONArray().also { a -> result.notFoundQueries.forEach(a::put) })
                }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect ->
                "Spotify needs playlist modification permission before I can add songs. Reconnect Spotify and try again."
            !result.ok ->
                result.error ?: "I could not add those songs to the Spotify playlist."
            result.addedCount > 0 -> buildString {
                append("Added ")
                append(result.addedCount)
                append(if (result.addedCount == 1) " song" else " songs")
                append(" to ")
                append(result.playlistName ?: playlist)
                append(".")
                if (result.notFoundQueries.isNotEmpty()) {
                    append(" I could not find ")
                    append(result.notFoundQueries.size)
                    append(if (result.notFoundQueries.size == 1) " song." else " songs.")
                }
            }
            else -> "I did not find any specific requested songs to add."
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_ADD_TO_PLAYLIST,
            content = content,
            chatResponse = chatResponse,
            uiAction = if (result.needsReconnect) {
                AssistantUiAction(
                    type = AssistantUiActionType.CONNECT_SPOTIFY,
                    label = "Reconnect Spotify"
                )
            } else {
                null
            }
        )
    }

    private suspend fun executeSpotifyGetPlaybackState(): SharedToolExecutionResult {
        val result = spotifyService.getPlaybackState()
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_GET_PLAYBACK_STATE)
            .put("is_playing", result.isPlaying)
            .put("shuffle", result.shuffle)
            .put("repeat_mode", result.repeatMode)
            .put("progress_ms", result.progressMs)
            .put("volume_percent", result.volumePercent)
            .also { c ->
                result.deviceName?.let { c.put("device_name", it) }
                result.deviceId?.let { c.put("device_id", it) }
                result.track?.let { track ->
                    c.put(
                        "track",
                        JSONObject()
                            .put("name", track.name)
                            .put("uri", track.uri)
                            .put("artists", JSONArray().also { a -> track.artists.forEach(a::put) })
                            .put("album", track.album)
                            .put("duration_ms", track.durationMs)
                    )
                }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs updated permissions before I can read playback state. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not read Spotify playback state."
            result.track == null -> "Spotify is not currently playing a track."
            result.isPlaying -> "Spotify is playing ${result.track.name} by ${result.track.artists.joinToString(", ")}."
            else -> "Spotify is paused on ${result.track.name} by ${result.track.artists.joinToString(", ")}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_GET_PLAYBACK_STATE,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifyControlPlayback(arguments: JSONObject): SharedToolExecutionResult {
        val action = arguments.optString("action").trim()
        val result = spotifyService.controlPlayback(action)
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_CONTROL_PLAYBACK)
            .put("action", action)
            .also { c ->
                result.deviceName?.let { c.put("device_name", it) }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs playback permission before I can control playback. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not control Spotify playback."
            else -> "Spotify playback ${actionLabel(action)}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_CONTROL_PLAYBACK,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifySetPlaybackOptions(arguments: JSONObject): SharedToolExecutionResult {
        val volume = if (arguments.has("volume_percent")) arguments.optInt("volume_percent") else null
        val shuffle = if (arguments.has("shuffle")) arguments.optBoolean("shuffle") else null
        val repeatMode = arguments.optString("repeat_mode").trim().ifBlank { null }
        val result = spotifyService.setPlaybackOptions(volume, shuffle, repeatMode)

        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS)
            .put("applied", JSONArray().also { a -> result.appliedOptions.forEach(a::put) })
            .also { c ->
                volume?.let { c.put("volume_percent", it) }
                shuffle?.let { c.put("shuffle", it) }
                repeatMode?.let { c.put("repeat_mode", it) }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs playback permission before I can set those options. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not update Spotify playback options."
            else -> "Updated Spotify playback options."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_SET_PLAYBACK_OPTIONS,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifyListPlaylistTracks(arguments: JSONObject): SharedToolExecutionResult {
        val playlist = arguments.optString("playlist").trim()
        if (playlist.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS, "playlist")
        }
        val result = spotifyService.listPlaylistTracks(
            playlistQuery = playlist,
            limit = arguments.optInt("limit", 25),
            offset = arguments.optInt("offset", 0)
        )
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS)
            .put("playlist", result.playlistName ?: playlist)
            .put("offset", result.offset)
            .put("limit", result.limit)
            .put("total", result.total)
            .put("tracks", JSONArray().also { arr -> result.tracks.forEach { arr.put(trackJson(it)) } })
            .also { c ->
                result.playlistUri?.let { c.put("playlist_uri", it) }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs playlist read permission before I can list those tracks. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not list that Spotify playlist."
            result.tracks.isEmpty() -> "${result.playlistName ?: playlist} has no matching tracks."
            else -> "${result.playlistName ?: playlist} includes ${result.tracks.take(5).joinToString(", ") { it.name }}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLIST_TRACKS,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifyRemoveFromPlaylist(arguments: JSONObject): SharedToolExecutionResult {
        val playlist = arguments.optString("playlist").trim()
        if (playlist.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST, "playlist")
        }
        val trackQueries = readStringList(arguments, "track_queries")
        val songUris = readStringList(arguments, "song_uris")
        if (trackQueries.isEmpty() && songUris.isEmpty()) {
            return spotifySimpleError(SharedToolSchemas.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST, "Missing songs to remove.")
        }

        val result = spotifyService.removeSongsFromPlaylist(playlist, trackQueries, songUris)
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST)
            .put("playlist", result.playlistName ?: playlist)
            .put("removed_count", result.removedCount)
            .also { c ->
                result.playlistUri?.let { c.put("playlist_uri", it) }
                if (result.notFoundQueries.isNotEmpty()) {
                    c.put("not_found", JSONArray().also { a -> result.notFoundQueries.forEach(a::put) })
                }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs playlist modification permission before I can remove songs. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not remove those songs from the playlist."
            else -> "Removed ${result.removedCount} ${if (result.removedCount == 1) "song" else "songs"} from ${result.playlistName ?: playlist}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_REMOVE_FROM_PLAYLIST,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifyUpdatePlaylist(arguments: JSONObject): SharedToolExecutionResult {
        val playlist = arguments.optString("playlist").trim()
        if (playlist.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_UPDATE_PLAYLIST, "playlist")
        }
        val result = spotifyService.updatePlaylist(
            playlistQuery = playlist,
            newName = arguments.optString("name").trim().ifBlank { null },
            description = if (arguments.has("description")) arguments.optString("description") else null,
            descriptionSet = arguments.has("description"),
            isPublic = if (arguments.has("public")) arguments.optBoolean("public") else null
        )
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_UPDATE_PLAYLIST)
            .put("playlist", result.playlistName ?: playlist)
            .also { c ->
                result.playlistUri?.let { c.put("playlist_uri", it) }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs playlist modification permission before I can update that playlist. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not update that Spotify playlist."
            else -> "Updated ${result.playlistName ?: playlist}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_UPDATE_PLAYLIST,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifyReorderPlaylist(arguments: JSONObject): SharedToolExecutionResult {
        val playlist = arguments.optString("playlist").trim()
        if (playlist.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_REORDER_PLAYLIST, "playlist")
        }
        val result = spotifyService.reorderPlaylist(
            playlistQuery = playlist,
            rangeStart = arguments.optInt("range_start", -1),
            insertBefore = arguments.optInt("insert_before", -1),
            rangeLength = arguments.optInt("range_length", 1),
            trackQuery = arguments.optString("track_query").trim().ifBlank { null },
            songUri = arguments.optString("song_uri").trim().ifBlank { null },
            beforeTrackQuery = arguments.optString("before_track_query").trim().ifBlank { null },
            beforeSongUri = arguments.optString("before_song_uri").trim().ifBlank { null },
            destination = arguments.optString("destination").trim().ifBlank { null }
        )
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_REORDER_PLAYLIST)
            .put("playlist", result.playlistName ?: playlist)
            .put("range_start", result.rangeStart)
            .put("insert_before", result.insertBefore)
            .put("range_length", result.rangeLength)
            .also { c ->
                result.playlistUri?.let { c.put("playlist_uri", it) }
                result.snapshotId?.let { c.put("snapshot_id", it) }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs playlist modification permission before I can reorder that playlist. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not reorder that Spotify playlist."
            else -> "Reordered ${result.playlistName ?: playlist}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_REORDER_PLAYLIST,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifySearch(arguments: JSONObject): SharedToolExecutionResult {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_SEARCH, "search")
        }
        val type = arguments.optString("type").trim()
        val result = spotifyService.search(query, type, arguments.optInt("limit", 10))
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_SEARCH)
            .put("query", query)
            .put("type", type)
            .put("items", JSONArray().also { arr -> result.items.forEach { arr.put(searchItemJson(it)) } })
            .also { c ->
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs updated permissions before I can search. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not search Spotify."
            result.items.isEmpty() -> "I found no Spotify matches."
            else -> "Found ${result.items.size} Spotify ${if (result.items.size == 1) "match" else "matches"}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_SEARCH,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifyLibrary(arguments: JSONObject): SharedToolExecutionResult {
        val action = arguments.optString("action").trim()
        val itemType = arguments.optString("item_type").trim().ifBlank { "track" }
        val result = spotifyService.library(
            action = action,
            itemType = itemType,
            queries = readStringList(arguments, "queries"),
            uris = readStringList(arguments, "uris"),
            limit = arguments.optInt("limit", 20),
            offset = arguments.optInt("offset", 0)
        )
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_LIBRARY)
            .put("action", action)
            .put("item_type", itemType)
            .put("changed_count", result.changedCount)
            .put("items", JSONArray().also { arr -> result.items.forEach { arr.put(trackJson(it)) } })
            .also { c ->
                if (result.containsResults.isNotEmpty()) {
                    c.put("contains", JSONArray().also { arr ->
                        result.containsResults.forEach { contains ->
                            arr.put(JSONObject().put("uri", contains.uri).put("saved", contains.saved))
                        }
                    })
                }
                if (result.notFoundQueries.isNotEmpty()) {
                    c.put("not_found", JSONArray().also { a -> result.notFoundQueries.forEach(a::put) })
                }
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs library permission before I can do that. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not update your Spotify library."
            action == "list_saved_tracks" -> "Found ${result.items.size} saved Spotify ${if (result.items.size == 1) "track" else "tracks"}."
            action == "contains_items" -> "Checked ${result.containsResults.size} Spotify library ${if (result.containsResults.size == 1) "item" else "items"}."
            else -> "Updated ${result.changedCount} Spotify library ${if (result.changedCount == 1) "item" else "items"}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_LIBRARY,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifyTopItems(arguments: JSONObject): SharedToolExecutionResult {
        val type = arguments.optString("type").trim()
        val result = spotifyService.topItems(
            type = type,
            timeRange = arguments.optString("time_range").trim().ifBlank { "medium_term" },
            limit = arguments.optInt("limit", 10)
        )
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_TOP_ITEMS)
            .put("type", type)
            .put("time_range", result.timeRange)
            .put("items", JSONArray().also { arr -> result.items.forEach { arr.put(searchItemJson(it)) } })
            .also { c ->
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs top items permission before I can read that. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not read your Spotify top items."
            else -> "Found ${result.items.size} Spotify top ${type.ifBlank { "items" }}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_TOP_ITEMS,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeSpotifyArtistTopTracks(arguments: JSONObject): SharedToolExecutionResult {
        val artist = arguments.optString("artist").trim()
        if (artist.isBlank()) {
            return missingQueryResult(SharedToolSchemas.TOOL_SPOTIFY_ARTIST_TOP_TRACKS, "artist")
        }
        val result = spotifyService.artistTopTracks(
            artistQuery = artist,
            market = arguments.optString("market").trim().ifBlank { "from_token" }
        )
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_SPOTIFY_ARTIST_TOP_TRACKS)
            .put("artist", result.artistName ?: artist)
            .put("artist_uri", result.artistUri)
            .put("tracks", JSONArray().also { arr -> result.tracks.forEach { arr.put(trackJson(it)) } })
            .also { c ->
                result.error?.let { c.put("error", it) }
                if (result.needsReconnect) c.put("needs_reconnect", true)
            }

        val chatResponse = when {
            result.needsReconnect -> "Spotify needs updated permissions before I can read artist top tracks. Reconnect Spotify and try again."
            !result.ok -> result.error ?: "I could not read that artist's top tracks."
            else -> "Found ${result.tracks.size} top tracks for ${result.artistName ?: artist}."
        }

        return spotifyResult(
            toolName = SharedToolSchemas.TOOL_SPOTIFY_ARTIST_TOP_TRACKS,
            content = content,
            chatResponse = chatResponse,
            needsReconnect = result.needsReconnect
        )
    }

    private suspend fun executeCheckEmails(arguments: JSONObject): SharedToolExecutionResult {
        val query = arguments.optString("query").trim().ifBlank { null }
        val max = arguments.optInt("max", 10)
        val sinceHours = if (arguments.has("since_hours")) arguments.optInt("since_hours") else null

        val result = googleAccountService.fetchRecentEmails(query, max, sinceHours)
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_CHECK_EMAILS)
            .also { c ->
                result.query?.let { c.put("query", it) }
                result.error?.let { c.put("error", it) }
                if (result.needsConnect) c.put("needs_connect", true)
                c.put("emails", JSONArray().also { arr ->
                    result.emails.forEach { email ->
                        arr.put(
                            JSONObject()
                                .put("message_id", email.id)
                                .put("thread_id", email.threadId)
                                .put("from", email.from)
                                .put("subject", email.subject)
                                .put("snippet", email.snippet)
                                .put("received", email.received)
                        )
                    }
                })
            }

        val chatResponse = when {
            result.needsConnect ->
                "I need your Google account connected before I can read email."
            !result.ok ->
                result.error ?: "I could not read your Gmail just now."
            result.emails.isEmpty() ->
                "You have no matching emails."
            else -> buildString {
                append("You have ")
                append(result.emails.size)
                append(if (result.emails.size == 1) " email" else " emails")
                append(". ")
                append(result.emails.take(5).joinToString(" ") { email ->
                    "From ${senderName(email.from)} about ${email.subject}."
                })
            }
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_CHECK_EMAILS,
            content = content,
            chatResponse = chatResponse,
            uiAction = if (result.needsConnect) {
                AssistantUiAction(
                    type = AssistantUiActionType.CONNECT_GOOGLE,
                    label = "Connect Google"
                )
            } else {
                null
            }
        )
    }

    private suspend fun executeReadEmail(arguments: JSONObject): SharedToolExecutionResult {
        val messageId = arguments.optString("message_id").trim()
        if (messageId.isBlank()) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_READ_EMAIL,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_READ_EMAIL)
                    .put("error", "Missing message_id."),
                chatResponse = "I need the email message ID first."
            )
        }

        val result = googleAccountService.fetchEmailMessage(messageId)
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_READ_EMAIL)
            .put("message_id", messageId)
            .also { c ->
                result.message?.let { email ->
                    c.put("thread_id", email.threadId)
                    c.put("from", email.from)
                    c.put("to", email.to)
                    c.put("subject", email.subject)
                    c.put("received", email.received)
                    c.put("snippet", email.snippet)
                    c.put("body", email.body)
                }
                result.error?.let { c.put("error", it) }
                if (result.needsConnect) c.put("needs_connect", true)
            }

        val chatResponse = when {
            result.needsConnect ->
                "I need your Google account connected before I can read email."
            !result.ok ->
                result.error ?: "I could not read that Gmail message just now."
            result.message == null ->
                "I could not find that Gmail message."
            else -> buildString {
                append("From ")
                append(senderName(result.message.from))
                append(" about ")
                append(result.message.subject)
                append(". ")
                append(result.message.body.ifBlank { result.message.snippet }.take(500))
            }
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_READ_EMAIL,
            content = content,
            chatResponse = chatResponse,
            uiAction = if (result.needsConnect) {
                AssistantUiAction(
                    type = AssistantUiActionType.CONNECT_GOOGLE,
                    label = "Connect Google"
                )
            } else {
                null
            }
        )
    }

    private suspend fun executeCheckCalendar(arguments: JSONObject): SharedToolExecutionResult {
        val range = arguments.optString("range").trim().ifBlank { "today" }
        val date = arguments.optString("date").trim().ifBlank { null }
        val dateRange = arguments.optString("date_range").trim().ifBlank { null }
        val result = googleAccountService.fetchAgenda(range, date, dateRange)
        val content = JSONObject()
            .put("ok", result.ok)
            .put("tool", SharedToolSchemas.TOOL_CHECK_CALENDAR)
            .put("range", range)
            .also { c ->
                date?.let { c.put("date", it) }
                dateRange?.let { c.put("date_range", it) }
                result.error?.let { c.put("error", it) }
                if (result.needsConnect) c.put("needs_connect", true)
                c.put("events", JSONArray().also { arr ->
                    result.events.forEach { event ->
                        arr.put(
                            JSONObject()
                                .put("title", event.title)
                                .put("start", event.start)
                                .put("end", event.end)
                                .also { e ->
                                    event.location?.let { e.put("location", it) }
                                    event.id?.let { e.put("id", it) }
                                    event.htmlLink?.let { e.put("html_link", it) }
                                }
                        )
                    }
                })
            }

        val rangeLabel = when (range) {
            "tomorrow" -> "tomorrow"
            "next_24h" -> "in the next 24 hours"
            else -> "today"
        }
        val chatResponse = when {
            result.needsConnect ->
                "I need your Google account connected before I can read your calendar."
            !result.ok ->
                result.error ?: "I could not read your Google Calendar just now."
            result.events.isEmpty() ->
                "You have nothing on your calendar $rangeLabel."
            else -> buildString {
                append("You have ")
                append(result.events.size)
                append(if (result.events.size == 1) " event " else " events ")
                append(rangeLabel)
                append(". ")
                append(result.events.take(6).joinToString(" ") { it.title + "." })
            }
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_CHECK_CALENDAR,
            content = content,
            chatResponse = chatResponse,
            uiAction = if (result.needsConnect) {
                AssistantUiAction(
                    type = AssistantUiActionType.CONNECT_GOOGLE,
                    label = "Connect Google"
                )
            } else {
                null
            }
        )
    }

    private fun readStringList(arguments: JSONObject, key: String): List<String> {
        val array = arguments.optJSONArray(key)
        if (array != null) {
            return (0 until array.length())
                .mapNotNull { array.optString(it).trim().ifBlank { null } }
        }
        return arguments.optString(key).trim().ifBlank { null }?.let { listOf(it) } ?: emptyList()
    }

    private fun trackJson(track: SpotifyTrackSummary): JSONObject {
        return JSONObject()
            .put("name", track.name)
            .put("uri", track.uri)
            .put("artists", JSONArray().also { a -> track.artists.forEach(a::put) })
            .put("album", track.album)
            .put("duration_ms", track.durationMs)
    }

    private fun searchItemJson(item: SpotifySearchItem): JSONObject {
        return JSONObject()
            .put("type", item.type)
            .put("name", item.name)
            .put("uri", item.uri)
            .also { c ->
                item.subtitle?.let { c.put("subtitle", it) }
                item.ownerName?.let { c.put("owner_name", it) }
                item.trackCount?.let { c.put("track_count", it) }
                item.popularity?.let { c.put("popularity", it) }
            }
    }

    private fun spotifyResult(
        toolName: String,
        content: JSONObject,
        chatResponse: String,
        needsReconnect: Boolean
    ): SharedToolExecutionResult {
        return SharedToolExecutionResult(
            toolName = toolName,
            content = content,
            chatResponse = chatResponse,
            uiAction = if (needsReconnect) {
                AssistantUiAction(
                    type = AssistantUiActionType.CONNECT_SPOTIFY,
                    label = "Reconnect Spotify"
                )
            } else {
                null
            }
        )
    }

    private fun spotifySimpleError(toolName: String, error: String): SharedToolExecutionResult {
        return SharedToolExecutionResult(
            toolName = toolName,
            content = JSONObject()
                .put("ok", false)
                .put("tool", toolName)
                .put("error", error),
            chatResponse = error
        )
    }

    private fun actionLabel(action: String): String {
        return when (action) {
            "pause" -> "paused"
            "resume" -> "resumed"
            "next" -> "skipped to the next track"
            "previous" -> "went to the previous track"
            else -> "updated"
        }
    }

    private fun senderName(from: String): String {
        // "Alice Smith <alice@x.com>" -> "Alice Smith"; "bob@x.com" -> "bob@x.com"
        val angle = from.indexOf('<')
        val name = if (angle > 0) from.substring(0, angle).trim().trim('"') else from.trim()
        return name.ifBlank { from.trim() }
    }

    private fun missingQueryResult(toolName: String, noun: String): SharedToolExecutionResult {
        return SharedToolExecutionResult(
            toolName = toolName,
            content = JSONObject()
                .put("ok", false)
                .put("tool", toolName)
                .put("error", "Missing $noun query."),
            chatResponse = "I need the $noun name first."
        )
    }

    private fun buildSpotifyPlaybackContent(
        toolName: String,
        playback: SpotifyPlaybackResult
    ): JSONObject {
        return JSONObject()
            .put("ok", playback.ok)
            .put("tool", toolName)
            .put("query", playback.query)
            .also { content ->
                playback.itemName?.let { content.put("item_name", it) }
                playback.subtitle?.let { content.put("subtitle", it) }
                playback.itemUri?.let { content.put("item_uri", it) }
                playback.deviceName?.let { content.put("device_name", it) }
                playback.error?.let { content.put("error", it) }
            }
    }

    private fun buildSpotifyPlaybackMessage(playback: SpotifyPlaybackResult): String {
        if (!playback.ok) {
            return playback.error ?: "Spotify playback failed."
        }

        val itemLabel = playback.itemName?.takeIf { it.isNotBlank() } ?: "your selection"
        val subtitle = playback.subtitle?.takeIf { it.isNotBlank() }
        val deviceName = playback.deviceName?.takeIf { it.isNotBlank() }

        return when (playback.contentType) {
            "song" -> buildString {
                append("Playing ")
                append(itemLabel)
                subtitle?.let {
                    append(" by ")
                    append(it)
                }
                deviceName?.let {
                    append(" on ")
                    append(it)
                }
                append(" in Spotify.")
            }
            "album" -> buildString {
                append("Playing the album ")
                append(itemLabel)
                subtitle?.let {
                    append(" by ")
                    append(it)
                }
                deviceName?.let {
                    append(" on ")
                    append(it)
                }
                append(" in Spotify.")
            }
            else -> buildString {
                append("Playing your Spotify playlist ")
                append(itemLabel)
                deviceName?.let {
                    append(" on ")
                    append(it)
                }
                append(".")
            }
        }
    }

    private fun buildSpotifyPlaylistMessage(playlists: SpotifyPlaylistListResult): String {
        if (!playlists.ok) {
            return playlists.error ?: "I could not load your Spotify playlists."
        }

        val names = playlists.playlists.map { it.name }
        val joinedNames = when (names.size) {
            0 -> "none"
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + ", and ${names.last()}"
        }

        return if (playlists.query.isNullOrBlank()) {
            "Your Spotify playlists include $joinedNames."
        } else {
            "The matching Spotify playlists are $joinedNames."
        }
    }
}

internal data class SharedToolExecutionResult(
    val toolName: String,
    val content: JSONObject,
    val chatResponse: String,
    val uiAction: AssistantUiAction? = null
)

data class AssistantUiAction(
    val type: AssistantUiActionType,
    val label: String,
    val intentAction: String? = null,
    val dataUri: String? = null
)

enum class AssistantUiActionType {
    CONNECT_SPOTIFY,
    CONNECT_GOOGLE,
    OPEN_SETTINGS
}
