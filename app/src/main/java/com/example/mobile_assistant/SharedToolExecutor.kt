package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal class SharedToolExecutor(
    private val searchWeb: suspend (String) -> SearchWebResult,
    private val callToolService: CallToolService,
    private val spotifyService: SpotifyService,
    private val clockToolService: ClockToolService,
    private val smsToolService: SmsToolService,
    private val whatsAppToolService: WhatsAppToolService
) {
    suspend fun execute(toolName: String, arguments: JSONObject): SharedToolExecutionResult? {
        return when (toolName) {
            SharedToolSchemas.TOOL_SEARCH_WEB -> executeSearchWeb(arguments)
            SharedToolSchemas.TOOL_CALL_CONTACT -> callToolService.executeCall(arguments)
            SharedToolSchemas.TOOL_SEND_SMS -> smsToolService.executeSendSms(arguments)
            SharedToolSchemas.TOOL_SEND_WHATSAPP -> whatsAppToolService.executeSend(arguments)
            SharedToolSchemas.TOOL_CLOCK_TIMER -> clockToolService.executeTimer(arguments)
            SharedToolSchemas.TOOL_CLOCK_ALARM -> clockToolService.executeAlarm(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG -> executeSpotifyPlaySong(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_PLAY_ALBUM -> executeSpotifyPlayAlbum(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_PLAY_PLAYLIST -> executeSpotifyPlayPlaylist(arguments)
            SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLISTS -> executeSpotifyListPlaylists(arguments)
            else -> null
        }
    }

    suspend fun executeFirstMatching(toolCalls: JSONArray?): SharedToolExecutionResult? {
        if (toolCalls == null) return null
        for (index in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(index) ?: continue
            val function = toolCall.optJSONObject("function") ?: continue
            val toolName = function.optString("name").trim()
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

        val playback = spotifyService.playPlaylist(query)
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
    val chatResponse: String
)
