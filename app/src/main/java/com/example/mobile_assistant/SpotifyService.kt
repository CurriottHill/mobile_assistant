package com.example.mobile_assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

internal class SpotifyService(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val redirectUri = BuildConfig.SPOTIFY_REDIRECT_URI.trim()
    private val redirectTarget = runCatching { Uri.parse(redirectUri) }.getOrNull()
    private val random = SecureRandom()

    fun connectionStatus(): SpotifyConnectionStatus {
        if (!isConfigured()) {
            return SpotifyConnectionStatus(
                isConfigured = false,
                isConnected = false,
                statusText = "Spotify is not configured. Add SPOTIFY_CLIENT_ID and SPOTIFY_REDIRECT_URI to local.properties."
            )
        }

        val tokens = readTokens()
        if (tokens.accessToken.isBlank() || tokens.refreshToken.isBlank()) {
            return SpotifyConnectionStatus(
                isConfigured = true,
                isConnected = false,
                statusText = "Spotify is not connected."
            )
        }

        val label = tokens.displayName?.takeIf { it.isNotBlank() }
            ?: tokens.userId?.takeIf { it.isNotBlank() }
            ?: "Spotify account"
        val hasPlaylistModify = SpotifyScopePolicy.canModifyPrivatePlaylists(tokens.scopes)

        return SpotifyConnectionStatus(
            isConfigured = true,
            isConnected = true,
            hasPlaylistModify = hasPlaylistModify,
            statusText = if (hasPlaylistModify) {
                "Connected to $label with playlist permissions."
            } else {
                "Connected to $label, but playlist permissions are missing. Reconnect Spotify to create playlists."
            }
        )
    }

    fun isSpotifyRedirect(uri: Uri?): Boolean {
        val expected = redirectTarget ?: return false
        if (uri == null) return false
        if (!uri.scheme.equals(expected.scheme, ignoreCase = true)) return false
        if (!uri.host.equals(expected.host, ignoreCase = true)) return false
        val expectedPath = expected.path.orEmpty()
        return expectedPath.isBlank() || uri.path == expectedPath
    }

    fun createLoginIntent(): SpotifyLoginLaunchResult {
        if (!isConfigured()) {
            return SpotifyLoginLaunchResult(
                ok = false,
                message = "Spotify is not configured. Add SPOTIFY_CLIENT_ID and SPOTIFY_REDIRECT_URI to local.properties."
            )
        }

        clearStoredTokens()
        val verifier = generateCodeVerifier()
        val state = generateRandomToken(24)
        prefs.edit()
            .putString(KEY_PENDING_CODE_VERIFIER, verifier)
            .putString(KEY_PENDING_STATE, state)
            .apply()

        val authUri = Uri.parse(SPOTIFY_AUTHORIZE_URL)
            .buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallengeFor(verifier))
            .appendQueryParameter("scope", SPOTIFY_SCOPES.joinToString(" "))
            .appendQueryParameter("show_dialog", "true")
            .appendQueryParameter("state", state)
            .build()

        return SpotifyLoginLaunchResult(
            ok = true,
            message = "Open the browser to connect Spotify.",
            intent = Intent(Intent.ACTION_VIEW, authUri).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    suspend fun handleRedirect(uri: Uri): SpotifyAuthResult {
        if (!isSpotifyRedirect(uri)) {
            return SpotifyAuthResult(
                handled = false,
                ok = false,
                message = "Not a Spotify redirect."
            )
        }

        val authError = uri.getQueryParameter("error")?.trim()
        if (!authError.isNullOrBlank()) {
            clearPendingAuth()
            return SpotifyAuthResult(
                handled = true,
                ok = false,
                message = "Spotify login failed: ${authError.replace('_', ' ')}."
            )
        }

        val code = uri.getQueryParameter("code").orEmpty().trim()
        val state = uri.getQueryParameter("state").orEmpty().trim()
        val expectedState = prefs.getString(KEY_PENDING_STATE, null).orEmpty()
        val verifier = prefs.getString(KEY_PENDING_CODE_VERIFIER, null).orEmpty()

        if (code.isBlank() || verifier.isBlank() || state.isBlank()) {
            clearPendingAuth()
            return SpotifyAuthResult(
                handled = true,
                ok = false,
                message = "Spotify login could not be completed."
            )
        }
        if (state != expectedState) {
            clearPendingAuth()
            return SpotifyAuthResult(
                handled = true,
                ok = false,
                message = "Spotify login state did not match. Try connecting again."
            )
        }

        val tokenExchange = exchangeAuthorizationCode(code, verifier)
        clearPendingAuth()
        if (!tokenExchange.ok || tokenExchange.json == null) {
            return SpotifyAuthResult(
                handled = true,
                ok = false,
                message = tokenExchange.error ?: "Spotify login failed."
            )
        }

        saveTokenResponse(
            tokenJson = tokenExchange.json,
            preserveRefreshToken = false
        )
        refreshStoredProfileIfPossible()

        val status = connectionStatus()
        return SpotifyAuthResult(
            handled = true,
            ok = true,
            message = status.statusText
        )
    }

    suspend fun playSong(query: String): SpotifyPlaybackResult {
        return withContext(Dispatchers.IO) {
            playCatalogItem(
                contentType = "song",
                query = query,
                itemResolver = { accessToken, userQuery ->
                    resolveTrack(accessToken, userQuery)
                },
                requestBodyBuilder = { item ->
                    JSONObject().put("uris", JSONArray().put(item.uri))
                }
            )
        }
    }

    suspend fun playAlbum(query: String): SpotifyPlaybackResult {
        return withContext(Dispatchers.IO) {
            playCatalogItem(
                contentType = "album",
                query = query,
                itemResolver = { accessToken, userQuery ->
                    resolveAlbum(accessToken, userQuery)
                },
                requestBodyBuilder = { item ->
                    JSONObject().put("context_uri", item.uri)
                }
            )
        }
    }

    suspend fun playPlaylist(query: String, shuffle: Boolean? = null): SpotifyPlaybackResult {
        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            if (!tokenResult.ok || tokenResult.accessToken == null) {
                return@withContext SpotifyPlaybackResult(
                    ok = false,
                    contentType = "playlist",
                    query = query,
                    error = tokenResult.error ?: "Spotify is not connected."
                )
            }

            val matchedPlaylist = resolveUserPlaylist(tokenResult.accessToken, query)
            if (!matchedPlaylist.ok || matchedPlaylist.item == null) {
                return@withContext SpotifyPlaybackResult(
                    ok = false,
                    contentType = "playlist",
                    query = query,
                    error = matchedPlaylist.error ?: "No matching Spotify playlist was found."
                )
            }

            val deviceResult = ensurePlaybackDevice(tokenResult.accessToken)
            if (!deviceResult.ok || deviceResult.device == null) {
                return@withContext SpotifyPlaybackResult(
                    ok = false,
                    contentType = "playlist",
                    query = query,
                    itemName = matchedPlaylist.item.name,
                    itemUri = matchedPlaylist.item.uri,
                    error = deviceResult.error
                )
            }

            val playbackResponse = sendJsonRequest(
                Request.Builder()
                    .url(
                        "$SPOTIFY_API_BASE/me/player/play?device_id=${
                            Uri.encode(deviceResult.device.id)
                        }"
                    )
                    .addHeader("Authorization", "Bearer ${tokenResult.accessToken}")
                    .put(JSONObject().put("context_uri", matchedPlaylist.item.uri).toString()
                        .toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )

            if (!playbackResponse.ok) {
                return@withContext SpotifyPlaybackResult(
                    ok = false,
                    contentType = "playlist",
                    query = query,
                    itemName = matchedPlaylist.item.name,
                    itemUri = matchedPlaylist.item.uri,
                    error = playbackResponse.error ?: "Spotify playback failed."
                )
            }

            if (shuffle != null) {
                val shuffleResponse = sendJsonRequestBlocking(
                    Request.Builder()
                        .url(
                            "$SPOTIFY_API_BASE/me/player/shuffle?state=$shuffle&device_id=${
                                Uri.encode(deviceResult.device.id)
                            }"
                        )
                        .addHeader("Authorization", "Bearer ${tokenResult.accessToken}")
                        .put(ByteArray(0).toRequestBody(null))
                        .build()
                )
                if (!shuffleResponse.ok) {
                    return@withContext SpotifyPlaybackResult(
                        ok = false,
                        contentType = "playlist",
                        query = query,
                        itemName = matchedPlaylist.item.name,
                        subtitle = matchedPlaylist.item.ownerName,
                        itemUri = matchedPlaylist.item.uri,
                        deviceName = deviceResult.device.name,
                        error = shuffleResponse.error ?: "Spotify started the playlist, but shuffle could not be updated."
                    )
                }
            }

            val verificationError = verifyPlaylistPlaybackStarted(
                accessToken = tokenResult.accessToken,
                expectedPlaylistUri = matchedPlaylist.item.uri,
                expectedDeviceId = deviceResult.device.id,
                expectedShuffle = shuffle
            )
            if (verificationError != null) {
                return@withContext SpotifyPlaybackResult(
                    ok = false,
                    contentType = "playlist",
                    query = query,
                    itemName = matchedPlaylist.item.name,
                    subtitle = matchedPlaylist.item.ownerName,
                    itemUri = matchedPlaylist.item.uri,
                    deviceName = deviceResult.device.name,
                    error = verificationError
                )
            }

            return@withContext SpotifyPlaybackResult(
                ok = true,
                contentType = "playlist",
                query = query,
                itemName = matchedPlaylist.item.name,
                subtitle = matchedPlaylist.item.ownerName,
                itemUri = matchedPlaylist.item.uri,
                deviceName = deviceResult.device.name
            )
        }
    }

    suspend fun listPlaylists(query: String?, limit: Int): SpotifyPlaylistListResult {
        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            if (!tokenResult.ok || tokenResult.accessToken == null) {
                return@withContext SpotifyPlaylistListResult(
                    ok = false,
                    query = query,
                    error = tokenResult.error ?: "Spotify is not connected."
                )
            }

            val playlists = fetchUserPlaylists(tokenResult.accessToken)
            if (playlists.error != null) {
                return@withContext SpotifyPlaylistListResult(
                    ok = false,
                    query = query,
                    error = playlists.error
                )
            }

            val maxResults = limit.coerceIn(1, 20)
            val filtered = filterPlaylists(playlists.items, query).take(maxResults)
            if (filtered.isEmpty()) {
                return@withContext SpotifyPlaylistListResult(
                    ok = false,
                    query = query,
                    error = if (query.isNullOrBlank()) {
                        "No Spotify playlists were found."
                    } else {
                        "No matching Spotify playlists were found."
                    }
                )
            }

            return@withContext SpotifyPlaylistListResult(
                ok = true,
                query = query,
                playlists = filtered.map { playlist ->
                    SpotifyPlaylistSummary(
                        name = playlist.name,
                        uri = playlist.uri,
                        ownerName = playlist.ownerName,
                        trackCount = playlist.trackCount
                    )
                }
            )
        }
    }

    suspend fun getPlaybackState(): SpotifyPlaybackStateResult {
        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            val accessToken = tokenResult.accessToken
            if (!tokenResult.ok || accessToken == null) {
                return@withContext SpotifyPlaybackStateResult(
                    ok = false,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            val missingScope = SpotifyScopePolicy.missingAnyScope(
                scopes = readTokens().scopes,
                requiredScopes = listOf("user-read-playback-state", "user-read-currently-playing")
            )
            if (missingScope != null) {
                return@withContext SpotifyPlaybackStateResult(
                    ok = false,
                    error = "Spotify is connected but missing the $missingScope permission.",
                    needsReconnect = true
                )
            }

            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("$SPOTIFY_API_BASE/me/player")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            if (response.code == 204) {
                return@withContext SpotifyPlaybackStateResult(ok = true)
            }
            val json = response.json
            if (!response.ok || json == null) {
                return@withContext SpotifyPlaybackStateResult(
                    ok = false,
                    error = response.error ?: "Spotify playback state could not be loaded.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }

            val device = json.optJSONObject("device")
            return@withContext SpotifyPlaybackStateResult(
                ok = true,
                isPlaying = json.optBoolean("is_playing", false),
                shuffle = json.optBoolean("shuffle_state", false),
                repeatMode = json.optString("repeat_state").trim().ifBlank { "off" },
                progressMs = json.optInt("progress_ms", 0),
                volumePercent = device?.optInt("volume_percent") ?: 0,
                deviceId = device?.optString("id")?.trim()?.ifBlank { null },
                deviceName = device?.optString("name")?.trim()?.ifBlank { null },
                track = json.optJSONObject("item")?.toTrackSummary()
            )
        }
    }

    suspend fun controlPlayback(action: String): SpotifyPlaybackCommandResult {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in setOf("pause", "resume", "next", "previous")) {
            return SpotifyPlaybackCommandResult(
                ok = false,
                action = normalizedAction,
                error = "Invalid Spotify playback action."
            )
        }

        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            val accessToken = tokenResult.accessToken
            if (!tokenResult.ok || accessToken == null) {
                return@withContext SpotifyPlaybackCommandResult(
                    ok = false,
                    action = normalizedAction,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            SpotifyScopePolicy.missingScope(readTokens().scopes, "user-modify-playback-state")?.let { scope ->
                return@withContext SpotifyPlaybackCommandResult(
                    ok = false,
                    action = normalizedAction,
                    error = "Spotify is connected but missing the $scope permission.",
                    needsReconnect = true
                )
            }

            val deviceResult = ensurePlaybackDevice(accessToken)
            if (!deviceResult.ok || deviceResult.device == null) {
                return@withContext SpotifyPlaybackCommandResult(
                    ok = false,
                    action = normalizedAction,
                    error = deviceResult.error
                )
            }
            val endpoint = when (normalizedAction) {
                "pause" -> "$SPOTIFY_API_BASE/me/player/pause"
                "resume" -> "$SPOTIFY_API_BASE/me/player/play"
                "next" -> "$SPOTIFY_API_BASE/me/player/next"
                else -> "$SPOTIFY_API_BASE/me/player/previous"
            }
            val requestBuilder = Request.Builder()
                .url("$endpoint?device_id=${Uri.encode(deviceResult.device.id)}")
                .addHeader("Authorization", "Bearer $accessToken")
            val request = when (normalizedAction) {
                "pause", "resume" -> requestBuilder.put(ByteArray(0).toRequestBody(null)).build()
                else -> requestBuilder.post(ByteArray(0).toRequestBody(null)).build()
            }
            val response = sendJsonRequestBlocking(request)
            if (!response.ok) {
                return@withContext SpotifyPlaybackCommandResult(
                    ok = false,
                    action = normalizedAction,
                    deviceName = deviceResult.device.name,
                    error = response.error ?: "Spotify playback command failed.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }
            SpotifyPlaybackCommandResult(
                ok = true,
                action = normalizedAction,
                deviceName = deviceResult.device.name
            )
        }
    }

    suspend fun setPlaybackOptions(
        volumePercent: Int?,
        shuffle: Boolean?,
        repeatMode: String?
    ): SpotifyPlaybackOptionsResult {
        val normalizedRepeat = repeatMode?.trim()?.lowercase()?.ifBlank { null }
        if (volumePercent == null && shuffle == null && normalizedRepeat == null) {
            return SpotifyPlaybackOptionsResult(ok = false, error = "No playback option was provided.")
        }
        if (volumePercent != null && volumePercent !in 0..100) {
            return SpotifyPlaybackOptionsResult(ok = false, error = "volume_percent must be between 0 and 100.")
        }
        if (normalizedRepeat != null && normalizedRepeat !in setOf("off", "track", "context")) {
            return SpotifyPlaybackOptionsResult(ok = false, error = "repeat_mode must be off, track, or context.")
        }

        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            val accessToken = tokenResult.accessToken
            if (!tokenResult.ok || accessToken == null) {
                return@withContext SpotifyPlaybackOptionsResult(
                    ok = false,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            SpotifyScopePolicy.missingScope(readTokens().scopes, "user-modify-playback-state")?.let { scope ->
                return@withContext SpotifyPlaybackOptionsResult(
                    ok = false,
                    error = "Spotify is connected but missing the $scope permission.",
                    needsReconnect = true
                )
            }
            val deviceResult = ensurePlaybackDevice(accessToken)
            if (!deviceResult.ok || deviceResult.device == null) {
                return@withContext SpotifyPlaybackOptionsResult(ok = false, error = deviceResult.error)
            }

            val applied = mutableListOf<String>()
            fun sendOption(url: String, label: String): SpotifyHttpResult {
                val response = sendJsonRequestBlocking(
                    Request.Builder()
                        .url("$url&device_id=${Uri.encode(deviceResult.device.id)}")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .put(ByteArray(0).toRequestBody(null))
                        .build()
                )
                if (response.ok) applied += label
                return response
            }

            val responses = buildList {
                volumePercent?.let {
                    add(sendOption("$SPOTIFY_API_BASE/me/player/volume?volume_percent=$it", "volume_percent"))
                }
                shuffle?.let {
                    add(sendOption("$SPOTIFY_API_BASE/me/player/shuffle?state=$it", "shuffle"))
                }
                normalizedRepeat?.let {
                    add(sendOption("$SPOTIFY_API_BASE/me/player/repeat?state=$it", "repeat_mode"))
                }
            }
            val failed = responses.firstOrNull { !it.ok }
            if (failed != null) {
                return@withContext SpotifyPlaybackOptionsResult(
                    ok = false,
                    appliedOptions = applied,
                    error = failed.error ?: "Spotify playback options could not be updated.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(failed.code, failed.error)
                )
            }
            SpotifyPlaybackOptionsResult(ok = true, appliedOptions = applied)
        }
    }

    suspend fun listPlaylistTracks(
        playlistQuery: String,
        limit: Int,
        offset: Int
    ): SpotifyPlaylistTracksResult {
        return withContext(Dispatchers.IO) {
            val resolved = resolvePlaylistForEditOrRead(playlistQuery, needsModify = false, targetPublic = null)
            if (!resolved.ok || resolved.accessToken == null || resolved.playlist == null) {
                return@withContext SpotifyPlaylistTracksResult(
                    ok = false,
                    playlistName = resolved.playlist?.name,
                    playlistUri = resolved.playlist?.uri,
                    error = resolved.error,
                    needsReconnect = resolved.needsReconnect,
                    offset = offset.coerceAtLeast(0),
                    limit = limit.coerceIn(1, 50)
                )
            }
            val playlistId = extractSpotifyId(resolved.playlist.uri, "playlist")
            if (playlistId.isNullOrBlank()) {
                return@withContext SpotifyPlaylistTracksResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    error = "Spotify returned a playlist without a usable id."
                )
            }

            val max = limit.coerceIn(1, 50)
            val start = offset.coerceAtLeast(0)
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("${SpotifyPlaylistEndpoints.itemsUrl(SPOTIFY_API_BASE, playlistId)}?limit=$max&offset=$start")
                    .addHeader("Authorization", "Bearer ${resolved.accessToken}")
                    .get()
                    .build()
            )
            val json = response.json
            if (!response.ok || json == null) {
                return@withContext SpotifyPlaylistTracksResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    error = response.error ?: "Spotify playlist tracks could not be loaded.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error),
                    offset = start,
                    limit = max
                )
            }
            SpotifyPlaylistTracksResult(
                ok = true,
                playlistName = resolved.playlist.name,
                playlistUri = resolved.playlist.uri,
                tracks = parsePlaylistTrackItems(json.optJSONArray("items") ?: JSONArray()),
                total = json.optInt("total", 0),
                offset = start,
                limit = max
            )
        }
    }

    private fun isConfigured(): Boolean {
        return BuildConfig.SPOTIFY_CLIENT_ID.trim().isNotBlank() && redirectUri.isNotBlank()
    }

    private fun readTokens(): SpotifyStoredTokens {
        return SpotifyStoredTokens(
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty(),
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null).orEmpty(),
            expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L),
            displayName = prefs.getString(KEY_DISPLAY_NAME, null),
            userId = prefs.getString(KEY_USER_ID, null),
            scopes = prefs.getString(KEY_SCOPES, null).orEmpty()
        )
    }

    /**
     * True only if the stored grant can modify the assistant's default private playlists.
     * A refresh token keeps the scopes granted at consent time, so users connected before
     * playlist-modify-private was added will be false here until they reconnect.
     */
    fun connectionHasPlaylistModify(): Boolean {
        return SpotifyScopePolicy.canModifyPrivatePlaylists(readTokens().scopes)
    }

    private fun clearPendingAuth() {
        prefs.edit()
            .remove(KEY_PENDING_STATE)
            .remove(KEY_PENDING_CODE_VERIFIER)
            .apply()
    }

    private fun generateCodeVerifier(): String = generateRandomToken(64)

    private fun generateRandomToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ).replace("=", "")
    }

    private fun codeChallengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private suspend fun exchangeAuthorizationCode(code: String, verifier: String): SpotifyHttpResult {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID.trim())
            .add("code_verifier", verifier)
            .build()

        return sendFormRequest(
            Request.Builder()
                .url(SPOTIFY_TOKEN_URL)
                .post(body)
                .build()
        )
    }

    private suspend fun refreshAccessToken(refreshToken: String): SpotifyHttpResult {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID.trim())
            .build()

        return sendFormRequest(
            Request.Builder()
                .url(SPOTIFY_TOKEN_URL)
                .post(body)
                .build()
        )
    }

    private fun saveTokenResponse(tokenJson: JSONObject, preserveRefreshToken: Boolean) {
        val previous = readTokens()
        val accessToken = tokenJson.optString("access_token").trim()
        val refreshToken = tokenJson.optString("refresh_token").trim()
        val scope = tokenJson.optString("scope").trim()
        val expiresIn = tokenJson.optLong("expires_in", 0L)
        if (accessToken.isBlank()) return

        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(
                KEY_REFRESH_TOKEN,
                when {
                    refreshToken.isNotBlank() -> refreshToken
                    preserveRefreshToken -> previous.refreshToken
                    else -> ""
                }
            )
            .putString(
                KEY_SCOPES,
                SpotifyScopePolicy.scopesToStore(
                    responseScope = scope,
                    previousScope = previous.scopes,
                    preservePrevious = preserveRefreshToken,
                    requestedScope = SPOTIFY_SCOPE_STRING
                )
            )
            .putLong(KEY_EXPIRES_AT_MS, System.currentTimeMillis() + (expiresIn * 1000L))
            .apply()
    }

    private suspend fun refreshStoredProfileIfPossible() {
        val tokenResult = requireAccessToken(forceRefresh = false)
        val accessToken = tokenResult.accessToken ?: return
        val response = sendJsonRequest(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/me")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val profile = response.json ?: return
        prefs.edit()
            .putString(KEY_DISPLAY_NAME, profile.optString("display_name").trim().ifBlank { null })
            .putString(KEY_USER_ID, profile.optString("id").trim().ifBlank { null })
            .apply()
    }

    private suspend fun requireAccessToken(forceRefresh: Boolean = false): SpotifyAccessTokenResult {
        if (!isConfigured()) {
            return SpotifyAccessTokenResult(
                ok = false,
                error = "Spotify is not configured."
            )
        }

        val stored = readTokens()
        val now = System.currentTimeMillis()
        if (!forceRefresh &&
            stored.accessToken.isNotBlank() &&
            stored.expiresAtMs > now + TOKEN_REFRESH_SKEW_MS
        ) {
            return SpotifyAccessTokenResult(ok = true, accessToken = stored.accessToken)
        }

        if (stored.refreshToken.isBlank()) {
            return SpotifyAccessTokenResult(
                ok = false,
                error = "Spotify is not connected yet."
            )
        }

        val refreshResponse = refreshAccessToken(stored.refreshToken)
        if (!refreshResponse.ok || refreshResponse.json == null) {
            clearStoredTokens()
            return SpotifyAccessTokenResult(
                ok = false,
                error = refreshResponse.error ?: "Spotify login expired. Connect Spotify again."
            )
        }

        saveTokenResponse(
            tokenJson = refreshResponse.json,
            preserveRefreshToken = true
        )
        return SpotifyAccessTokenResult(
            ok = true,
            accessToken = readTokens().accessToken
        )
    }

    private fun clearStoredTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_USER_ID)
            .remove(KEY_SCOPES)
            .apply()
    }

    /**
     * Creates a new playlist in the user's account and optionally adds songs, entirely via
     * the Spotify Web API so the agent never drives the Spotify UI. Scope mismatches can't be
     * healed by a token refresh (refresh tokens keep their consent-time scopes), so callers
     * are told to reconnect when [needsReconnect][SpotifyCreatePlaylistResult.needsReconnect].
     */
    suspend fun createPlaylist(
        name: String,
        description: String?,
        isPublic: Boolean,
        trackQueries: List<String>,
        songUris: List<String>
    ): SpotifyCreatePlaylistResult {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return SpotifyCreatePlaylistResult(
                ok = false,
                name = name,
                error = "Missing playlist name."
            )
        }

        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            if (!tokenResult.ok || tokenResult.accessToken == null) {
                return@withContext SpotifyCreatePlaylistResult(
                    ok = false,
                    name = trimmedName,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            val missingPlaylistScope = SpotifyScopePolicy.missingPlaylistModifyScope(
                scopes = readTokens().scopes,
                isPublicPlaylist = isPublic
            )
            if (missingPlaylistScope != null) {
                return@withContext SpotifyCreatePlaylistResult(
                    ok = false,
                    name = trimmedName,
                    error = "Spotify is connected but missing the $missingPlaylistScope permission.",
                    needsReconnect = true
                )
            }

            val accessToken = tokenResult.accessToken
            val createResponse = sendJsonRequestBlocking(
                Request.Builder()
                    .url(SpotifyPlaylistEndpoints.createPlaylistUrl(SPOTIFY_API_BASE))
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(
                        SpotifyPlaylistPayloads.createBody(trimmedName, isPublic, description)
                            .toString()
                            .toRequestBody(JSON_MEDIA_TYPE)
                    )
                    .build()
            )
            if (!createResponse.ok || createResponse.json == null) {
                return@withContext SpotifyCreatePlaylistResult(
                    ok = false,
                    name = trimmedName,
                    error = createResponse.error ?: "Spotify could not create the playlist.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(
                        statusCode = createResponse.code,
                        errorMessage = createResponse.error
                    )
                )
            }

            val playlistId = createResponse.json.optString("id").trim()
            if (playlistId.isBlank()) {
                return@withContext SpotifyCreatePlaylistResult(
                    ok = false,
                    name = trimmedName,
                    error = "Spotify created a playlist but returned no id."
                )
            }
            val playlistUri = createResponse.json.optString("uri").trim()
                .ifBlank { "spotify:playlist:$playlistId" }

            val resolvedUris = mutableListOf<String>()
            val notFound = mutableListOf<String>()
            resolveRequestedTrackUris(accessToken, trackQueries, songUris, resolvedUris, notFound)

            var added = 0
            var addError: String? = null
            var addNeedsReconnect = false
            if (resolvedUris.isNotEmpty()) {
                val addResult = addTrackUrisToPlaylist(accessToken, playlistId, resolvedUris)
                added = addResult.addedCount
                addError = addResult.error
                addNeedsReconnect = addResult.needsReconnect
            }

            SpotifyCreatePlaylistResult(
                ok = addError == null,
                name = trimmedName,
                playlistUri = playlistUri,
                addedCount = added,
                notFoundQueries = notFound,
                needsReconnect = addNeedsReconnect,
                error = addError
            )
        }
    }

    suspend fun addSongsToPlaylist(
        playlistQuery: String,
        trackQueries: List<String>,
        songUris: List<String>
    ): SpotifyAddToPlaylistResult {
        val trimmedPlaylist = playlistQuery.trim()
        if (trimmedPlaylist.isBlank()) {
            return SpotifyAddToPlaylistResult(ok = false, error = "Missing playlist name.")
        }

        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            if (!tokenResult.ok || tokenResult.accessToken == null) {
                return@withContext SpotifyAddToPlaylistResult(
                    ok = false,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            val accessToken = tokenResult.accessToken
            val playlistResolution = resolveUserPlaylist(accessToken, trimmedPlaylist)
            val playlist = playlistResolution.item
            if (!playlistResolution.ok || playlist == null) {
                return@withContext SpotifyAddToPlaylistResult(
                    ok = false,
                    error = playlistResolution.error ?: "No matching Spotify playlist was found."
                )
            }

            val playlistId = extractSpotifyId(playlist.uri, "playlist")
            if (playlistId.isNullOrBlank()) {
                return@withContext SpotifyAddToPlaylistResult(
                    ok = false,
                    playlistName = playlist.name,
                    playlistUri = playlist.uri,
                    error = "Spotify returned a playlist without a usable id."
                )
            }

            val missingPlaylistScope = SpotifyScopePolicy.missingPlaylistModifyScope(
                scopes = readTokens().scopes,
                isPublicPlaylist = playlist.isPublic
            )
            if (missingPlaylistScope != null) {
                return@withContext SpotifyAddToPlaylistResult(
                    ok = false,
                    playlistName = playlist.name,
                    playlistUri = playlist.uri,
                    error = "Spotify is connected but missing the $missingPlaylistScope permission.",
                    needsReconnect = true
                )
            }

            val resolvedUris = mutableListOf<String>()
            val notFound = mutableListOf<String>()
            resolveRequestedTrackUris(accessToken, trackQueries, songUris, resolvedUris, notFound)
            if (resolvedUris.isEmpty()) {
                return@withContext SpotifyAddToPlaylistResult(
                    ok = false,
                    playlistName = playlist.name,
                    playlistUri = playlist.uri,
                    notFoundQueries = notFound,
                    error = "No specific requested songs were found."
                )
            }

            val addResult = addTrackUrisToPlaylist(accessToken, playlistId, resolvedUris)
            SpotifyAddToPlaylistResult(
                ok = addResult.error == null,
                playlistName = playlist.name,
                playlistUri = playlist.uri,
                addedCount = addResult.addedCount,
                notFoundQueries = notFound,
                needsReconnect = addResult.needsReconnect,
                error = addResult.error
            )
        }
    }

    suspend fun removeSongsFromPlaylist(
        playlistQuery: String,
        trackQueries: List<String>,
        songUris: List<String>
    ): SpotifyRemoveFromPlaylistResult {
        return withContext(Dispatchers.IO) {
            val resolved = resolvePlaylistForEditOrRead(playlistQuery, needsModify = true, targetPublic = null)
            if (!resolved.ok || resolved.accessToken == null || resolved.playlist == null) {
                return@withContext SpotifyRemoveFromPlaylistResult(
                    ok = false,
                    playlistName = resolved.playlist?.name,
                    playlistUri = resolved.playlist?.uri,
                    error = resolved.error,
                    needsReconnect = resolved.needsReconnect
                )
            }
            val playlistId = extractSpotifyId(resolved.playlist.uri, "playlist")
            if (playlistId.isNullOrBlank()) {
                return@withContext SpotifyRemoveFromPlaylistResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    error = "Spotify returned a playlist without a usable id."
                )
            }
            val resolvedUris = mutableListOf<String>()
            val notFound = mutableListOf<String>()
            resolveRequestedTrackUris(resolved.accessToken, trackQueries, songUris, resolvedUris, notFound)
            if (resolvedUris.isEmpty()) {
                return@withContext SpotifyRemoveFromPlaylistResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    notFoundQueries = notFound,
                    error = "No specific requested songs were found."
                )
            }

            var removed = 0
            for (chunk in SpotifyPlaylistPayloads.chunkUris(resolvedUris.distinct(), 100)) {
                val response = sendJsonRequestBlocking(
                    Request.Builder()
                        .url(SpotifyPlaylistEndpoints.itemsUrl(SPOTIFY_API_BASE, playlistId))
                        .addHeader("Authorization", "Bearer ${resolved.accessToken}")
                        .delete(
                            SpotifyPlaylistPayloads.removeTracksBody(chunk)
                                .toString()
                                .toRequestBody(JSON_MEDIA_TYPE)
                        )
                        .build()
                )
                if (!response.ok) {
                    return@withContext SpotifyRemoveFromPlaylistResult(
                        ok = false,
                        playlistName = resolved.playlist.name,
                        playlistUri = resolved.playlist.uri,
                        removedCount = removed,
                        notFoundQueries = notFound,
                        error = response.error ?: "Spotify could not remove those songs.",
                        needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                    )
                }
                removed += chunk.size
            }
            SpotifyRemoveFromPlaylistResult(
                ok = true,
                playlistName = resolved.playlist.name,
                playlistUri = resolved.playlist.uri,
                removedCount = removed,
                notFoundQueries = notFound
            )
        }
    }

    suspend fun updatePlaylist(
        playlistQuery: String,
        newName: String?,
        description: String?,
        descriptionSet: Boolean,
        isPublic: Boolean?
    ): SpotifyPlaylistUpdateResult {
        if (newName.isNullOrBlank() && !descriptionSet && isPublic == null) {
            return SpotifyPlaylistUpdateResult(ok = false, error = "No playlist update was provided.")
        }

        return withContext(Dispatchers.IO) {
            val resolved = resolvePlaylistForEditOrRead(
                playlistQuery = playlistQuery,
                needsModify = true,
                targetPublic = isPublic
            )
            if (!resolved.ok || resolved.accessToken == null || resolved.playlist == null) {
                return@withContext SpotifyPlaylistUpdateResult(
                    ok = false,
                    playlistName = resolved.playlist?.name,
                    playlistUri = resolved.playlist?.uri,
                    error = resolved.error,
                    needsReconnect = resolved.needsReconnect
                )
            }
            val playlistId = extractSpotifyId(resolved.playlist.uri, "playlist")
            if (playlistId.isNullOrBlank()) {
                return@withContext SpotifyPlaylistUpdateResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    error = "Spotify returned a playlist without a usable id."
                )
            }
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url(SpotifyPlaylistEndpoints.detailsUrl(SPOTIFY_API_BASE, playlistId))
                    .addHeader("Authorization", "Bearer ${resolved.accessToken}")
                    .put(
                        SpotifyPlaylistPayloads.updateDetailsBody(
                            name = newName,
                            description = description,
                            descriptionSet = descriptionSet,
                            isPublic = isPublic
                        ).toString().toRequestBody(JSON_MEDIA_TYPE)
                    )
                    .build()
            )
            if (!response.ok) {
                return@withContext SpotifyPlaylistUpdateResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    error = response.error ?: "Spotify could not update that playlist.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }
            SpotifyPlaylistUpdateResult(
                ok = true,
                playlistName = newName?.takeIf { it.isNotBlank() } ?: resolved.playlist.name,
                playlistUri = resolved.playlist.uri
            )
        }
    }

    suspend fun reorderPlaylist(
        playlistQuery: String,
        rangeStart: Int,
        insertBefore: Int,
        rangeLength: Int,
        trackQuery: String? = null,
        songUri: String? = null,
        beforeTrackQuery: String? = null,
        beforeSongUri: String? = null,
        destination: String? = null
    ): SpotifyPlaylistReorderResult {
        val validationError = SpotifyPlaybackValidation.validateReorderInputs(
            rangeStart = rangeStart,
            insertBefore = insertBefore,
            rangeLength = rangeLength,
            trackQuery = trackQuery,
            songUri = songUri,
            beforeTrackQuery = beforeTrackQuery,
            beforeSongUri = beforeSongUri,
            destination = destination
        )
        if (validationError != null) {
            return SpotifyPlaylistReorderResult(
                ok = false,
                rangeStart = rangeStart,
                insertBefore = insertBefore,
                rangeLength = rangeLength,
                error = validationError
            )
        }

        return withContext(Dispatchers.IO) {
            val resolved = resolvePlaylistForEditOrRead(playlistQuery, needsModify = true, targetPublic = null)
            if (!resolved.ok || resolved.accessToken == null || resolved.playlist == null) {
                return@withContext SpotifyPlaylistReorderResult(
                    ok = false,
                    playlistName = resolved.playlist?.name,
                    playlistUri = resolved.playlist?.uri,
                    rangeStart = rangeStart,
                    insertBefore = insertBefore,
                    rangeLength = rangeLength,
                    error = resolved.error,
                    needsReconnect = resolved.needsReconnect
                )
            }
            val playlistId = extractSpotifyId(resolved.playlist.uri, "playlist")
            if (playlistId.isNullOrBlank()) {
                return@withContext SpotifyPlaylistReorderResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    rangeStart = rangeStart,
                    insertBefore = insertBefore,
                    rangeLength = rangeLength,
                    error = "Spotify returned a playlist without a usable id."
                )
            }
            val playlistTracks = if (rangeStart < 0 || insertBefore < 0) {
                fetchAllPlaylistTracks(
                    accessToken = resolved.accessToken,
                    playlistId = playlistId,
                    playlist = resolved.playlist
                )
            } else {
                PlaylistTrackLoadResult(ok = true)
            }
            if (!playlistTracks.ok) {
                return@withContext SpotifyPlaylistReorderResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    rangeStart = rangeStart,
                    insertBefore = insertBefore,
                    rangeLength = rangeLength,
                    error = playlistTracks.error ?: "Spotify playlist tracks could not be loaded.",
                    needsReconnect = playlistTracks.needsReconnect
                )
            }

            val moveTrackResolution = if (rangeStart >= 0) {
                PlaylistTrackReferenceResolution(index = rangeStart)
            } else {
                resolvePlaylistTrackReference(
                    tracks = playlistTracks.tracks,
                    trackQuery = trackQuery,
                    songUri = songUri,
                    label = "song to move"
                )
            }
            if (moveTrackResolution.error != null || moveTrackResolution.index == null) {
                return@withContext SpotifyPlaylistReorderResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    rangeStart = rangeStart,
                    insertBefore = insertBefore,
                    rangeLength = rangeLength,
                    error = moveTrackResolution.error ?: "I could not identify the song to move in that playlist."
                )
            }

            val resolvedInsertBefore = when {
                insertBefore >= 0 -> PlaylistTrackReferenceResolution(index = insertBefore)
                destination.equals("top", ignoreCase = true) -> PlaylistTrackReferenceResolution(index = 0)
                destination.equals("bottom", ignoreCase = true) -> PlaylistTrackReferenceResolution(index = playlistTracks.tracks.size)
                else -> resolvePlaylistTrackReference(
                    tracks = playlistTracks.tracks,
                    trackQuery = beforeTrackQuery,
                    songUri = beforeSongUri,
                    label = "destination song"
                )
            }
            if (resolvedInsertBefore.error != null || resolvedInsertBefore.index == null) {
                return@withContext SpotifyPlaylistReorderResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    rangeStart = moveTrackResolution.index,
                    insertBefore = insertBefore,
                    rangeLength = rangeLength,
                    error = resolvedInsertBefore.error ?: "I could not determine where to move that song."
                )
            }

            val resolvedRangeStart = moveTrackResolution.index
            val finalInsertBefore = resolvedInsertBefore.index
            val resolvedValidationError = SpotifyPlaybackValidation.validateReorder(
                rangeStart = resolvedRangeStart,
                insertBefore = finalInsertBefore,
                rangeLength = rangeLength
            )
            if (resolvedValidationError != null) {
                return@withContext SpotifyPlaylistReorderResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    rangeStart = resolvedRangeStart,
                    insertBefore = finalInsertBefore,
                    rangeLength = rangeLength,
                    error = resolvedValidationError
                )
            }
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url(SpotifyPlaylistEndpoints.updateItemsUrl(SPOTIFY_API_BASE, playlistId))
                    .addHeader("Authorization", "Bearer ${resolved.accessToken}")
                    .put(
                        SpotifyPlaylistPayloads.reorderBody(resolvedRangeStart, finalInsertBefore, rangeLength)
                            .toString()
                            .toRequestBody(JSON_MEDIA_TYPE)
                    )
                    .build()
            )
            if (!response.ok) {
                return@withContext SpotifyPlaylistReorderResult(
                    ok = false,
                    playlistName = resolved.playlist.name,
                    playlistUri = resolved.playlist.uri,
                    rangeStart = resolvedRangeStart,
                    insertBefore = finalInsertBefore,
                    rangeLength = rangeLength,
                    error = response.error ?: "Spotify could not reorder that playlist.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }
            SpotifyPlaylistReorderResult(
                ok = true,
                playlistName = resolved.playlist.name,
                playlistUri = resolved.playlist.uri,
                rangeStart = resolvedRangeStart,
                insertBefore = finalInsertBefore,
                rangeLength = rangeLength,
                snapshotId = response.json?.optString("snapshot_id")?.trim()?.ifBlank { null }
            )
        }
    }

    suspend fun search(query: String, type: String, limit: Int): SpotifySearchResult {
        val normalizedType = type.trim().lowercase()
        if (normalizedType !in setOf("track", "album", "artist", "playlist")) {
            return SpotifySearchResult(ok = false, error = "Invalid Spotify search type.")
        }
        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            val accessToken = tokenResult.accessToken
            if (!tokenResult.ok || accessToken == null) {
                return@withContext SpotifySearchResult(
                    ok = false,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            val max = limit.coerceIn(1, 20)
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("$SPOTIFY_API_BASE/search?q=${Uri.encode(query)}&type=$normalizedType&limit=$max")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            if (!response.ok || response.json == null) {
                return@withContext SpotifySearchResult(
                    ok = false,
                    error = response.error ?: "Spotify search failed.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }
            SpotifySearchResult(
                ok = true,
                items = parseSearchItems(response.json, normalizedType)
            )
        }
    }

    suspend fun library(
        action: String,
        itemType: String,
        queries: List<String>,
        uris: List<String>,
        limit: Int,
        offset: Int
    ): SpotifyLibraryResult {
        val normalizedAction = action.trim().lowercase()
        val normalizedType = itemType.trim().lowercase().ifBlank { "track" }
        if (normalizedAction !in setOf("list_saved_tracks", "save_items", "remove_items", "contains_items")) {
            return SpotifyLibraryResult(ok = false, error = "Invalid Spotify library action.")
        }
        if (normalizedType !in setOf("track", "album")) {
            return SpotifyLibraryResult(ok = false, error = "item_type must be track or album.")
        }

        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            val accessToken = tokenResult.accessToken
            if (!tokenResult.ok || accessToken == null) {
                return@withContext SpotifyLibraryResult(
                    ok = false,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            val requiredScope = if (normalizedAction == "list_saved_tracks" || normalizedAction == "contains_items") {
                "user-library-read"
            } else {
                "user-library-modify"
            }
            SpotifyScopePolicy.missingScope(readTokens().scopes, requiredScope)?.let { scope ->
                return@withContext SpotifyLibraryResult(
                    ok = false,
                    error = "Spotify is connected but missing the $scope permission.",
                    needsReconnect = true
                )
            }
            if (normalizedAction == "list_saved_tracks") {
                return@withContext listSavedTracks(accessToken, limit, offset)
            }

            val resolvedIds = mutableListOf<String>()
            val notFound = mutableListOf<String>()
            resolveLibraryItemIds(accessToken, normalizedType, queries, uris, resolvedIds, notFound)
            if (resolvedIds.isEmpty()) {
                return@withContext SpotifyLibraryResult(
                    ok = false,
                    notFoundQueries = notFound,
                    error = "No specific Spotify library items were found."
                )
            }
            when (normalizedAction) {
                "contains_items" -> containsLibraryItems(accessToken, normalizedType, resolvedIds, notFound)
                "save_items" -> mutateLibraryItems(accessToken, normalizedType, resolvedIds, notFound, save = true)
                else -> mutateLibraryItems(accessToken, normalizedType, resolvedIds, notFound, save = false)
            }
        }
    }

    suspend fun topItems(type: String, timeRange: String, limit: Int): SpotifyTopItemsResult {
        val normalizedType = type.trim().lowercase()
        val normalizedRange = timeRange.trim().lowercase().ifBlank { "medium_term" }
        if (normalizedType !in setOf("tracks", "artists")) {
            return SpotifyTopItemsResult(ok = false, timeRange = normalizedRange, error = "type must be tracks or artists.")
        }
        if (normalizedRange !in setOf("short_term", "medium_term", "long_term")) {
            return SpotifyTopItemsResult(ok = false, timeRange = normalizedRange, error = "Invalid top items time_range.")
        }
        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            val accessToken = tokenResult.accessToken
            if (!tokenResult.ok || accessToken == null) {
                return@withContext SpotifyTopItemsResult(
                    ok = false,
                    timeRange = normalizedRange,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            SpotifyScopePolicy.missingScope(readTokens().scopes, "user-top-read")?.let { scope ->
                return@withContext SpotifyTopItemsResult(
                    ok = false,
                    timeRange = normalizedRange,
                    error = "Spotify is connected but missing the $scope permission.",
                    needsReconnect = true
                )
            }
            val max = limit.coerceIn(1, 20)
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("$SPOTIFY_API_BASE/me/top/$normalizedType?time_range=$normalizedRange&limit=$max")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            if (!response.ok || response.json == null) {
                return@withContext SpotifyTopItemsResult(
                    ok = false,
                    timeRange = normalizedRange,
                    error = response.error ?: "Spotify top items could not be loaded.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }
            SpotifyTopItemsResult(
                ok = true,
                timeRange = normalizedRange,
                items = parseTopItems(response.json.optJSONArray("items") ?: JSONArray(), normalizedType)
            )
        }
    }

    suspend fun artistTopTracks(artistQuery: String, market: String): SpotifyArtistTopTracksResult {
        return withContext(Dispatchers.IO) {
            val tokenResult = requireAccessToken()
            val accessToken = tokenResult.accessToken
            if (!tokenResult.ok || accessToken == null) {
                return@withContext SpotifyArtistTopTracksResult(
                    ok = false,
                    error = tokenResult.error ?: "Spotify is not connected.",
                    needsReconnect = true
                )
            }
            val artist = resolveArtist(accessToken, artistQuery)
            if (!artist.ok || artist.item == null) {
                return@withContext SpotifyArtistTopTracksResult(
                    ok = false,
                    error = artist.error ?: "No matching Spotify artist was found."
                )
            }
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("$SPOTIFY_API_BASE/artists/${extractSpotifyId(artist.item.uri, "artist")}/top-tracks?market=${Uri.encode(market.ifBlank { "from_token" })}")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            if (!response.ok || response.json == null) {
                return@withContext SpotifyArtistTopTracksResult(
                    ok = false,
                    artistName = artist.item.name,
                    artistUri = artist.item.uri,
                    error = response.error ?: "Spotify artist top tracks could not be loaded.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }
            SpotifyArtistTopTracksResult(
                ok = true,
                artistName = artist.item.name,
                artistUri = artist.item.uri,
                tracks = parseTrackItems(response.json.optJSONArray("tracks") ?: JSONArray())
            )
        }
    }

    private suspend fun resolvePlaylistForEditOrRead(
        playlistQuery: String,
        needsModify: Boolean,
        targetPublic: Boolean?
    ): SpotifyResolvedPlaylistAccess {
        val tokenResult = requireAccessToken()
        val accessToken = tokenResult.accessToken
        if (!tokenResult.ok || accessToken == null) {
            return SpotifyResolvedPlaylistAccess(
                ok = false,
                error = tokenResult.error ?: "Spotify is not connected.",
                needsReconnect = true
            )
        }
        val playlistResolution = resolveUserPlaylist(accessToken, playlistQuery.trim())
        val playlist = playlistResolution.item
        if (!playlistResolution.ok || playlist == null) {
            return SpotifyResolvedPlaylistAccess(
                ok = false,
                error = playlistResolution.error ?: "No matching Spotify playlist was found."
            )
        }
        if (!needsModify) {
            SpotifyScopePolicy.missingAnyScope(
                scopes = readTokens().scopes,
                requiredScopes = listOf("playlist-read-private", "playlist-read-collaborative")
            )?.let { scope ->
                return SpotifyResolvedPlaylistAccess(
                    ok = false,
                    accessToken = accessToken,
                    playlist = playlist,
                    error = "Spotify is connected but missing the $scope permission.",
                    needsReconnect = true
                )
            }
            return SpotifyResolvedPlaylistAccess(ok = true, accessToken = accessToken, playlist = playlist)
        }

        val scopeTargetIsPublic = targetPublic ?: playlist.isPublic
        val missingPlaylistScope = SpotifyScopePolicy.missingPlaylistModifyScope(
            scopes = readTokens().scopes,
            isPublicPlaylist = scopeTargetIsPublic
        )
        if (missingPlaylistScope != null) {
            return SpotifyResolvedPlaylistAccess(
                ok = false,
                accessToken = accessToken,
                playlist = playlist,
                error = "Spotify is connected but missing the $missingPlaylistScope permission.",
                needsReconnect = true
            )
        }
        return SpotifyResolvedPlaylistAccess(ok = true, accessToken = accessToken, playlist = playlist)
    }

    private fun parsePlaylistTrackItems(items: JSONArray): List<SpotifyTrackSummary> {
        return buildList {
            for (index in 0 until items.length()) {
                val track = items.optJSONObject(index)?.optJSONObject("track") ?: continue
                track.toTrackSummary()?.let(::add)
            }
        }
    }

    private fun parseTrackItems(items: JSONArray): List<SpotifyTrackSummary> {
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.toTrackSummary()?.let(::add)
            }
        }
    }

    private fun fetchAllPlaylistTracks(
        accessToken: String,
        playlistId: String,
        playlist: SpotifyPlaylistItem
    ): PlaylistTrackLoadResult {
        val collected = mutableListOf<SpotifyTrackSummary>()
        var offset = 0
        while (true) {
            val pageOffset = offset
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("${SpotifyPlaylistEndpoints.itemsUrl(SPOTIFY_API_BASE, playlistId)}?limit=100&offset=$pageOffset")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            val json = response.json
            if (!response.ok || json == null) {
                return PlaylistTrackLoadResult(
                    ok = false,
                    tracks = collected,
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error),
                    error = response.error ?: "Spotify playlist tracks could not be loaded."
                )
            }
            val rawItems = json.optJSONArray("items") ?: JSONArray()
            val pageTracks = parsePlaylistTrackItems(rawItems)
            collected += pageTracks
            if (json.isNull("next") || rawItems.length() == 0) break
            offset += rawItems.length()
        }
        return PlaylistTrackLoadResult(ok = true, tracks = collected)
    }

    private fun resolvePlaylistTrackReference(
        tracks: List<SpotifyTrackSummary>,
        trackQuery: String?,
        songUri: String?,
        label: String
    ): PlaylistTrackReferenceResolution {
        songUri?.trim()?.takeIf { it.isNotBlank() }?.let { uri ->
            val exactIndex = tracks.indexOfFirst { it.uri.equals(uri, ignoreCase = true) }
            if (exactIndex >= 0) {
                return PlaylistTrackReferenceResolution(index = exactIndex)
            }
        }

        val query = trackQuery?.trim()?.takeIf { it.isNotBlank() }
        if (query.isNullOrBlank()) {
            return PlaylistTrackReferenceResolution(error = "Missing $label. Provide a song reference or index.")
        }

        val parsedQuery = SpotifyTrackSearchSupport.parseQuery(query)
        val scoredMatches = tracks.mapIndexedNotNull { index, track ->
            val score = SpotifyTrackSearchSupport.matchScore(
                requestedTitle = parsedQuery.title,
                requestedArtist = parsedQuery.artist,
                candidateTitle = track.name,
                candidateArtists = track.artists
            )
            if (score >= SpotifyTrackSearchSupport.MIN_ACCEPTABLE_SCORE) {
                PlaylistTrackCandidate(index = index, score = score)
            } else {
                null
            }
        }
        val best = scoredMatches.maxByOrNull { it.score }
            ?: return PlaylistTrackReferenceResolution(
                error = "No specific playlist track match was found for $query."
            )
        val bestCount = scoredMatches.count { it.score == best.score }
        if (bestCount > 1) {
            return PlaylistTrackReferenceResolution(
                error = "Multiple playlist tracks matched $query. Include the artist or use a direct URI."
            )
        }
        return PlaylistTrackReferenceResolution(index = best.index)
    }

    private fun parseSearchItems(json: JSONObject, type: String): List<SpotifySearchItem> {
        val key = when (type) {
            "track" -> "tracks"
            "album" -> "albums"
            "artist" -> "artists"
            else -> "playlists"
        }
        return parseTopItems(json.optJSONObject(key)?.optJSONArray("items") ?: JSONArray(), pluralSearchType(type))
    }

    private fun parseTopItems(items: JSONArray, type: String): List<SpotifySearchItem> {
        val normalized = type.removeSuffix("s")
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                when (normalized) {
                    "track" -> item.toTrackItem()?.let { track ->
                        add(
                            SpotifySearchItem(
                                type = "track",
                                name = track.name,
                                uri = track.uri,
                                subtitle = track.subtitle,
                                popularity = track.popularity
                            )
                        )
                    }
                    "album" -> item.toAlbumItem()?.let { album ->
                        add(
                            SpotifySearchItem(
                                type = "album",
                                name = album.name,
                                uri = album.uri,
                                subtitle = album.subtitle,
                                popularity = album.popularity
                            )
                        )
                    }
                    "artist" -> item.toArtistItem()?.let { artist ->
                        add(
                            SpotifySearchItem(
                                type = "artist",
                                name = artist.name,
                                uri = artist.uri,
                                popularity = artist.popularity
                            )
                        )
                    }
                    "playlist" -> item.toPlaylistItem()?.let { playlist ->
                        add(
                            SpotifySearchItem(
                                type = "playlist",
                                name = playlist.name,
                                uri = playlist.uri,
                                ownerName = playlist.ownerName,
                                trackCount = playlist.trackCount
                            )
                        )
                    }
                }
            }
        }
    }

    private fun pluralSearchType(type: String): String {
        return if (type.endsWith("s")) type else "${type}s"
    }

    private fun listSavedTracks(accessToken: String, limit: Int, offset: Int): SpotifyLibraryResult {
        val max = limit.coerceIn(1, 50)
        val start = offset.coerceAtLeast(0)
        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/me/tracks?limit=$max&offset=$start")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        if (!response.ok || response.json == null) {
            return SpotifyLibraryResult(
                ok = false,
                error = response.error ?: "Spotify saved tracks could not be loaded.",
                needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
            )
        }
        return SpotifyLibraryResult(
            ok = true,
            items = parsePlaylistTrackItems(response.json.optJSONArray("items") ?: JSONArray())
        )
    }

    private fun resolveLibraryItemIds(
        accessToken: String,
        itemType: String,
        queries: List<String>,
        uris: List<String>,
        resolvedIds: MutableList<String>,
        notFound: MutableList<String>
    ) {
        uris.map { it.trim() }.filter { it.isNotBlank() }.forEach { raw ->
            extractSpotifyId(raw, itemType)?.let(resolvedIds::add) ?: notFound.add(raw)
        }
        queries.map { it.trim() }.filter { it.isNotBlank() }.forEach { query ->
            val resolution = if (itemType == "album") {
                resolveAlbum(accessToken, query)
            } else {
                resolveTrack(accessToken, query)
            }
            val id = resolution.item?.uri?.let { extractSpotifyId(it, itemType) }
            if (resolution.ok && !id.isNullOrBlank()) {
                resolvedIds.add(id)
            } else {
                notFound.add(query)
            }
        }
    }

    private fun containsLibraryItems(
        accessToken: String,
        itemType: String,
        ids: List<String>,
        notFound: List<String>
    ): SpotifyLibraryResult {
        val contains = mutableListOf<SpotifyContainsResult>()
        for (chunk in SpotifyLibraryPayloads.chunkIds(ids.distinct(), 50)) {
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("${SpotifyLibraryEndpoints.containsUrl(SPOTIFY_API_BASE, itemType)}?ids=${chunk.joinToString(",") { Uri.encode(it) }}")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            if (!response.ok || response.jsonArray == null) {
                return SpotifyLibraryResult(
                    ok = false,
                    containsResults = contains,
                    notFoundQueries = notFound,
                    error = response.error ?: "Spotify library check failed.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }
            for (index in 0 until chunk.size) {
                contains += SpotifyContainsResult(
                    uri = "spotify:$itemType:${chunk[index]}",
                    saved = response.jsonArray.optBoolean(index, false)
                )
            }
        }
        return SpotifyLibraryResult(ok = true, containsResults = contains, notFoundQueries = notFound)
    }

    private fun mutateLibraryItems(
        accessToken: String,
        itemType: String,
        ids: List<String>,
        notFound: List<String>,
        save: Boolean
    ): SpotifyLibraryResult {
        var changed = 0
        for (chunk in SpotifyLibraryPayloads.chunkIds(ids.distinct(), 50)) {
            val body = SpotifyLibraryPayloads.idsBody(chunk).toString().toRequestBody(JSON_MEDIA_TYPE)
            val builder = Request.Builder()
                .url(SpotifyLibraryEndpoints.itemsUrl(SPOTIFY_API_BASE, itemType))
                .addHeader("Authorization", "Bearer $accessToken")
            val request = if (save) builder.put(body).build() else builder.delete(body).build()
            val response = sendJsonRequestBlocking(request)
            if (!response.ok) {
                return SpotifyLibraryResult(
                    ok = false,
                    changedCount = changed,
                    notFoundQueries = notFound,
                    error = response.error ?: "Spotify library update failed.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(response.code, response.error)
                )
            }
            changed += chunk.size
        }
        return SpotifyLibraryResult(ok = true, changedCount = changed, notFoundQueries = notFound)
    }

    private fun resolveRequestedTrackUris(
        accessToken: String,
        trackQueries: List<String>,
        songUris: List<String>,
        resolvedUris: MutableList<String>,
        notFound: MutableList<String>
    ) {
        songUris.map { it.trim() }.filter { it.isNotBlank() }.forEach(resolvedUris::add)
        for (query in trackQueries.map { it.trim() }.filter { it.isNotBlank() }) {
            val resolution = resolveTrack(accessToken, query)
            val uri = resolution.item?.uri
            if (resolution.ok && !uri.isNullOrBlank()) {
                resolvedUris.add(uri)
            } else {
                notFound.add(query)
            }
        }
    }

    private fun addTrackUrisToPlaylist(
        accessToken: String,
        playlistId: String,
        trackUris: List<String>
    ): SpotifyAddTracksResult {
        var added = 0
        for (chunk in SpotifyPlaylistPayloads.chunkUris(trackUris, 100)) {
            val addResponse = sendJsonRequestBlocking(
                Request.Builder()
                    .url(SpotifyPlaylistEndpoints.addItemsUrl(SPOTIFY_API_BASE, playlistId))
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(
                        SpotifyPlaylistPayloads.addTracksBody(chunk)
                            .toString()
                            .toRequestBody(JSON_MEDIA_TYPE)
                    )
                    .build()
            )
            if (addResponse.ok) {
                added += chunk.size
            } else {
                Log.w(
                    TAG,
                    "Spotify add items failed code=${addResponse.code} " +
                        "error=${addResponse.error} " +
                        "body=${addResponse.rawBody} " +
                        "playlist_id=$playlistId " +
                        "granted_scopes=${readTokens().scopes}"
                )
                return SpotifyAddTracksResult(
                    addedCount = added,
                    error = addResponse.error ?: "Some songs could not be added.",
                    needsReconnect = SpotifyErrorPolicy.needsReconnect(
                        statusCode = addResponse.code,
                        errorMessage = addResponse.error
                    )
                )
            }
        }
        return SpotifyAddTracksResult(addedCount = added)
    }

    private fun fetchCurrentUserId(accessToken: String): String? {
        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/me")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val id = response.json?.optString("id")?.trim()?.ifBlank { null } ?: return null
        prefs.edit().putString(KEY_USER_ID, id).apply()
        return id
    }

    private suspend fun playCatalogItem(
        contentType: String,
        query: String,
        itemResolver: (String, String) -> SpotifyItemResolution,
        requestBodyBuilder: (SpotifyCatalogItem) -> JSONObject
    ): SpotifyPlaybackResult {
        val tokenResult = requireAccessToken()
        if (!tokenResult.ok || tokenResult.accessToken == null) {
            return SpotifyPlaybackResult(
                ok = false,
                contentType = contentType,
                query = query,
                error = tokenResult.error ?: "Spotify is not connected."
            )
        }

        val itemResolution = itemResolver(tokenResult.accessToken, query)
        if (!itemResolution.ok || itemResolution.item == null) {
            return SpotifyPlaybackResult(
                ok = false,
                contentType = contentType,
                query = query,
                error = itemResolution.error ?: "Spotify could not find a matching $contentType."
            )
        }

        val deviceResult = ensurePlaybackDevice(tokenResult.accessToken)
        if (!deviceResult.ok || deviceResult.device == null) {
            return SpotifyPlaybackResult(
                ok = false,
                contentType = contentType,
                query = query,
                itemName = itemResolution.item.name,
                subtitle = itemResolution.item.subtitle,
                itemUri = itemResolution.item.uri,
                error = deviceResult.error
            )
        }

        val playbackResponse = sendJsonRequest(
            Request.Builder()
                .url(
                    "$SPOTIFY_API_BASE/me/player/play?device_id=${
                        Uri.encode(deviceResult.device.id)
                    }"
                )
                .addHeader("Authorization", "Bearer ${tokenResult.accessToken}")
                .put(requestBodyBuilder(itemResolution.item).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )

        if (!playbackResponse.ok) {
            return SpotifyPlaybackResult(
                ok = false,
                contentType = contentType,
                query = query,
                itemName = itemResolution.item.name,
                subtitle = itemResolution.item.subtitle,
                itemUri = itemResolution.item.uri,
                error = playbackResponse.error ?: "Spotify playback failed."
            )
        }

        return SpotifyPlaybackResult(
            ok = true,
            contentType = contentType,
            query = query,
            itemName = itemResolution.item.name,
            subtitle = itemResolution.item.subtitle,
            itemUri = itemResolution.item.uri,
            deviceName = deviceResult.device.name
        )
    }

    private fun resolveTrack(accessToken: String, query: String): SpotifyItemResolution {
        val directId = extractSpotifyId(query, "track")
        if (directId != null) {
            val directTrack = fetchTrackById(accessToken, directId)
            if (directTrack != null) {
                return SpotifyItemResolution(ok = true, item = directTrack)
            }
        }

        val parsedQuery = SpotifyTrackSearchSupport.parseQuery(query)
        val candidates = buildList {
            searchTrackCandidates(accessToken, parsedQuery.searchQuery).forEach(::add)
            if (parsedQuery.searchQuery != query.trim()) {
                searchTrackCandidates(accessToken, query.trim()).forEach(::add)
            }
        }.distinctBy { it.uri }

        val item = candidates
            .map { candidate ->
                candidate to SpotifyTrackSearchSupport.matchScore(
                    requestedTitle = parsedQuery.title,
                    requestedArtist = parsedQuery.artist,
                    candidateTitle = candidate.name,
                    candidateArtists = candidate.artistNames
                )
            }
            .filter { (_, score) -> score >= SpotifyTrackSearchSupport.MIN_ACCEPTABLE_SCORE }
            .maxWithOrNull(
                compareBy<Pair<SpotifyCatalogItem, Int>> { it.second }
                    .thenBy { it.first.popularity }
            )
            ?.first

        return if (item != null) {
            SpotifyItemResolution(ok = true, item = item)
        } else {
            SpotifyItemResolution(ok = false, error = "No specific Spotify song match was found for $query.")
        }
    }

    private fun searchTrackCandidates(accessToken: String, query: String): List<SpotifyCatalogItem> {
        if (query.isBlank()) return emptyList()
        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/search?q=${Uri.encode(query)}&type=track&limit=10")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val items = response.json
            ?.optJSONObject("tracks")
            ?.optJSONArray("items")
            ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.toTrackItem()?.let(::add)
            }
        }
    }

    private fun resolveAlbum(accessToken: String, query: String): SpotifyItemResolution {
        val directId = extractSpotifyId(query, "album")
        if (directId != null) {
            val directAlbum = fetchAlbumById(accessToken, directId)
            if (directAlbum != null) {
                return SpotifyItemResolution(ok = true, item = directAlbum)
            }
        }

        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url(
                    "$SPOTIFY_API_BASE/search?q=${Uri.encode(query)}&type=album&limit=1"
                )
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val item = response.json
            ?.optJSONObject("albums")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
            ?.toAlbumItem()

        return if (item != null) {
            SpotifyItemResolution(ok = true, item = item)
        } else {
            SpotifyItemResolution(ok = false, error = response.error ?: "No matching Spotify album was found.")
        }
    }

    private fun resolveArtist(accessToken: String, query: String): SpotifyItemResolution {
        val directId = extractSpotifyId(query, "artist")
        if (directId != null) {
            val directArtist = fetchArtistById(accessToken, directId)
            if (directArtist != null) {
                return SpotifyItemResolution(ok = true, item = directArtist)
            }
        }

        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/search?q=${Uri.encode(query)}&type=artist&limit=1")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val item = response.json
            ?.optJSONObject("artists")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
            ?.toArtistItem()

        return if (item != null) {
            SpotifyItemResolution(ok = true, item = item)
        } else {
            SpotifyItemResolution(ok = false, error = response.error ?: "No matching Spotify artist was found.")
        }
    }

    private fun resolveUserPlaylist(accessToken: String, query: String): SpotifyPlaylistResolution {
        val playlists = fetchUserPlaylists(accessToken)
        if (playlists.error != null) {
            return SpotifyPlaylistResolution(ok = false, error = playlists.error)
        }

        val directId = extractSpotifyId(query, "playlist")
        if (directId != null) {
            val directUri = "spotify:playlist:$directId"
            val directPlaylist = playlists.items.firstOrNull { it.uri.equals(directUri, ignoreCase = true) }
            return if (directPlaylist != null) {
                SpotifyPlaylistResolution(ok = true, item = directPlaylist)
            } else {
                SpotifyPlaylistResolution(
                    ok = false,
                    error = "That Spotify playlist is not in your library."
                )
            }
        }

        val matched = filterPlaylists(playlists.items, query).firstOrNull()
        return if (matched != null) {
            SpotifyPlaylistResolution(ok = true, item = matched)
        } else {
            SpotifyPlaylistResolution(ok = false, error = "No matching Spotify playlist was found in your library.")
        }
    }

    private fun fetchTrackById(accessToken: String, trackId: String): SpotifyCatalogItem? {
        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/tracks/$trackId")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        return response.json?.toTrackItem()
    }

    private fun fetchAlbumById(accessToken: String, albumId: String): SpotifyCatalogItem? {
        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/albums/$albumId")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        return response.json?.toAlbumItem()
    }

    private fun fetchArtistById(accessToken: String, artistId: String): SpotifyCatalogItem? {
        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/artists/$artistId")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        return response.json?.toArtistItem()
    }

    private fun fetchPlaylistById(accessToken: String, playlistId: String): SpotifyPlaylistItem? {
        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/playlists/$playlistId")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        return response.json?.toPlaylistItem()
    }

    private fun fetchUserPlaylists(accessToken: String): SpotifyPlaylistPage {
        val collected = mutableListOf<SpotifyPlaylistItem>()
        var offset = 0
        val pageSize = 50

        while (offset < MAX_PLAYLIST_FETCH) {
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("$SPOTIFY_API_BASE/me/playlists?limit=$pageSize&offset=$offset")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            val json = response.json
            if (!response.ok || json == null) {
                return SpotifyPlaylistPage(
                    items = collected,
                    error = response.error ?: "Spotify playlists could not be loaded."
                )
            }

            val items = json.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break
            for (index in 0 until items.length()) {
                val playlist = items.optJSONObject(index)?.toPlaylistItem() ?: continue
                collected += playlist
            }

            if (json.isNull("next")) break
            offset += pageSize
        }

        return SpotifyPlaylistPage(items = collected)
    }

    private fun filterPlaylists(playlists: List<SpotifyPlaylistItem>, query: String?): List<SpotifyPlaylistItem> {
        if (query.isNullOrBlank()) return playlists

        return playlists
            .map { playlist -> playlist to playlistMatchScore(query, playlist.name) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<SpotifyPlaylistItem, Int>> { it.second }
                    .thenBy { it.first.name.lowercase() }
            )
            .map { it.first }
    }

    private fun playlistMatchScore(query: String, playlistName: String): Int {
        val normalizedQuery = normalizeLookupText(query)
        val normalizedName = normalizeLookupText(playlistName)
        if (normalizedQuery.isBlank() || normalizedName.isBlank()) return 0
        if (normalizedQuery == normalizedName) return 1000
        if (normalizedName.startsWith(normalizedQuery)) return 850
        if (normalizedName.contains(normalizedQuery)) return 700

        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        val nameTokens = normalizedName.split(' ').filter { it.isNotBlank() }.toSet()
        if (queryTokens.isEmpty()) return 0

        val matchingTokenCount = queryTokens.count { token ->
            nameTokens.any { playlistToken ->
                playlistToken == token || playlistToken.startsWith(token) || token.startsWith(playlistToken)
            }
        }
        if (matchingTokenCount == 0) return 0

        return 400 + (matchingTokenCount * 100)
    }

    private fun normalizeLookupText(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun ensurePlaybackDevice(accessToken: String): SpotifyDeviceResolution {
        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url("$SPOTIFY_API_BASE/me/player/devices")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val devices = response.json?.optJSONArray("devices") ?: JSONArray()
        val parsedDevices = buildList {
            for (index in 0 until devices.length()) {
                val item = devices.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    SpotifyDevice(
                        id = id,
                        name = item.optString("name").trim().ifBlank { "Spotify device" },
                        isActive = item.optBoolean("is_active"),
                        isRestricted = item.optBoolean("is_restricted")
                    )
                )
            }
        }
        val device = parsedDevices.firstOrNull { it.isActive && !it.isRestricted }
            ?: parsedDevices.firstOrNull { !it.isRestricted }

        if (device == null) {
            return SpotifyDeviceResolution(
                ok = false,
                error = "Spotify is connected, but no available playback device was found. Open Spotify on a phone, computer, or speaker first."
            )
        }

        if (!device.isActive) {
            val transferBody = JSONObject()
                .put("device_ids", JSONArray().put(device.id))
                .put("play", false)
            val transferResponse = sendJsonRequestBlocking(
                Request.Builder()
                    .url("$SPOTIFY_API_BASE/me/player")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .put(transferBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
            if (!transferResponse.ok) {
                return SpotifyDeviceResolution(
                    ok = false,
                    error = transferResponse.error ?: "Spotify could not switch to an available playback device."
                )
            }
        }

        return SpotifyDeviceResolution(ok = true, device = device)
    }

    private suspend fun verifyPlaylistPlaybackStarted(
        accessToken: String,
        expectedPlaylistUri: String,
        expectedDeviceId: String,
        expectedShuffle: Boolean?
    ): String? {
        repeat(4) { attempt ->
            if (attempt > 0) delay(350)
            val response = sendJsonRequestBlocking(
                Request.Builder()
                    .url("$SPOTIFY_API_BASE/me/player")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            val json = response.json ?: return@repeat
            val currentDeviceId = json.optJSONObject("device")
                ?.optString("id")
                ?.trim()
                ?.ifBlank { null }
            val currentContextUri = json.optJSONObject("context")
                ?.optString("uri")
                ?.trim()
                ?.ifBlank { null }
            val shuffleMatches = expectedShuffle == null || json.optBoolean("shuffle_state", false) == expectedShuffle

            if (currentDeviceId == expectedDeviceId &&
                currentContextUri.equals(expectedPlaylistUri, ignoreCase = true) &&
                shuffleMatches
            ) {
                return null
            }
        }

        return "Spotify accepted the request, but the playlist did not start on the active device."
    }

    private fun extractSpotifyId(value: String, expectedType: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null

        val directUriMatch = Regex("^spotify:$expectedType:([^?]+)$", RegexOption.IGNORE_CASE)
            .find(trimmed)
        if (directUriMatch != null) {
            return directUriMatch.groupValues.getOrNull(1)?.trim()?.ifBlank { null }
        }

        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = parsed.host ?: return null
        if (!host.contains("spotify.com", ignoreCase = true)) return null
        val segments = parsed.pathSegments
        if (segments.size < 2) return null
        if (!segments[0].equals(expectedType, ignoreCase = true)) return null
        return segments[1].trim().ifBlank { null }
    }

    private fun JSONObject.toTrackItem(): SpotifyCatalogItem? {
        val uri = SpotifyUriSupport.canonicalUri(optString("uri"), "track", optString("id")) ?: return null
        val artistNames = optJSONArray("artists")
            ?.let { artists ->
                buildList {
                    for (index in 0 until artists.length()) {
                        val artistName = artists.optJSONObject(index)?.optString("name").orEmpty().trim()
                        if (artistName.isNotBlank()) add(artistName)
                    }
                }
            }
            .orEmpty()
        return SpotifyCatalogItem(
            name = optString("name").trim().ifBlank { "Spotify song" },
            subtitle = artistNames.joinToString(", ").ifBlank { null },
            uri = uri,
            artistNames = artistNames,
            popularity = optInt("popularity", 0)
        )
    }

    private fun JSONObject.toTrackSummary(): SpotifyTrackSummary? {
        val uri = SpotifyUriSupport.canonicalUri(optString("uri"), "track", optString("id")) ?: return null
        val artistNames = optJSONArray("artists")
            ?.let { artists ->
                buildList {
                    for (index in 0 until artists.length()) {
                        val artistName = artists.optJSONObject(index)?.optString("name").orEmpty().trim()
                        if (artistName.isNotBlank()) add(artistName)
                    }
                }
            }
            .orEmpty()
        return SpotifyTrackSummary(
            name = optString("name").trim().ifBlank { "Spotify song" },
            uri = uri,
            artists = artistNames,
            album = optJSONObject("album")?.optString("name")?.trim()?.ifBlank { null },
            durationMs = optInt("duration_ms", 0)
        )
    }

    private fun JSONObject.toAlbumItem(): SpotifyCatalogItem? {
        val uri = SpotifyUriSupport.canonicalUri(optString("uri"), "album", optString("id")) ?: return null
        val artistNames = optJSONArray("artists")
            ?.let { artists ->
                buildList {
                    for (index in 0 until artists.length()) {
                        val artistName = artists.optJSONObject(index)?.optString("name").orEmpty().trim()
                        if (artistName.isNotBlank()) add(artistName)
                    }
                }
            }
            .orEmpty()
        return SpotifyCatalogItem(
            name = optString("name").trim().ifBlank { "Spotify album" },
            subtitle = artistNames.joinToString(", ").ifBlank { null },
            uri = uri,
            artistNames = artistNames,
            popularity = optInt("popularity", 0)
        )
    }

    private fun JSONObject.toArtistItem(): SpotifyCatalogItem? {
        val uri = SpotifyUriSupport.canonicalUri(optString("uri"), "artist", optString("id")) ?: return null
        return SpotifyCatalogItem(
            name = optString("name").trim().ifBlank { "Spotify artist" },
            subtitle = null,
            uri = uri,
            artistNames = listOf(optString("name").trim()).filter { it.isNotBlank() },
            popularity = optInt("popularity", 0)
        )
    }

    private fun JSONObject.toPlaylistItem(): SpotifyPlaylistItem? {
        val uri = SpotifyUriSupport.canonicalUri(optString("uri"), "playlist", optString("id")) ?: return null
        val name = optString("name").trim().ifBlank { "Spotify playlist" }
        val owner = optJSONObject("owner")
        return SpotifyPlaylistItem(
            name = name,
            uri = uri,
            ownerName = owner?.optString("display_name")?.trim()?.ifBlank {
                owner.optString("id").trim().ifBlank { null }
            },
            trackCount = optJSONObject("tracks")?.optInt("total"),
            isPublic = optBoolean("public", false)
        )
    }

    private suspend fun sendFormRequest(request: Request): SpotifyHttpResult {
        return withContext(Dispatchers.IO) {
            sendRequest(request)
        }
    }

    private suspend fun sendJsonRequest(request: Request): SpotifyHttpResult {
        return withContext(Dispatchers.IO) {
            sendRequest(request)
        }
    }

    private fun sendJsonRequestBlocking(request: Request): SpotifyHttpResult {
        return sendRequest(request)
    }

    private fun sendRequest(request: Request): SpotifyHttpResult {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = body.takeIf { it.isNotBlank() }?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                }
                val jsonArray = body.takeIf { it.isNotBlank() }?.let {
                    runCatching { JSONArray(it) }.getOrNull()
                }
                if (response.isSuccessful) {
                    SpotifyHttpResult(ok = true, json = json, jsonArray = jsonArray, code = response.code)
                } else {
                    SpotifyHttpResult(
                        ok = false,
                        json = json,
                        jsonArray = jsonArray,
                        code = response.code,
                        rawBody = body.take(1_000),
                        error = extractSpotifyError(body, response.code)
                    )
                }
            }
        } catch (e: Exception) {
            SpotifyHttpResult(
                ok = false,
                error = e.message ?: "Spotify request failed."
            )
        }
    }

    private fun extractSpotifyError(responseBody: String, statusCode: Int): String {
        val json = runCatching { JSONObject(responseBody) }.getOrNull()
        val nestedError = json?.optJSONObject("error")
        val nestedMessage = nestedError?.optString("message")?.trim()
        val directDescription = json?.optString("error_description")?.trim()
        val directError = json?.optString("error")?.trim()
        val bestMessage = nestedMessage
            ?.takeIf { it.isNotBlank() }
            ?: directDescription?.takeIf { it.isNotBlank() }
            ?: directError?.takeIf { it.isNotBlank() }
            ?: responseBody.replace(Regex("\\s+"), " ").trim().take(200)

        return when (statusCode) {
            401 -> "Spotify login expired. Connect Spotify again."
            403 -> bestMessage.ifBlank { "Spotify rejected that playback request." }
            404 -> bestMessage.ifBlank {
                "Spotify could not find an active playback device."
            }
            else -> bestMessage.ifBlank { "Spotify request failed with HTTP $statusCode." }
        }
    }

    companion object {
        private const val TAG = "SpotifyService"
        private const val PREFS_NAME = "spotify_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SCOPES = "granted_scopes"
        private const val KEY_PENDING_STATE = "pending_state"
        private const val KEY_PENDING_CODE_VERIFIER = "pending_code_verifier"
        private const val SPOTIFY_AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SPOTIFY_API_BASE = "https://api.spotify.com/v1"
        private const val MAX_PLAYLIST_FETCH = 250
        private const val TOKEN_REFRESH_SKEW_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SPOTIFY_SCOPES = listOf(
            "user-read-private",
            "user-read-playback-state",
            "user-read-currently-playing",
            "user-modify-playback-state",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-private",
            "playlist-modify-public",
            "user-library-read",
            "user-library-modify",
            "user-top-read"
        )
        private val SPOTIFY_SCOPE_STRING = SPOTIFY_SCOPES.joinToString(" ")
    }
}

internal data class SpotifyConnectionStatus(
    val isConfigured: Boolean,
    val isConnected: Boolean,
    val hasPlaylistModify: Boolean = false,
    val statusText: String
)

internal data class SpotifyLoginLaunchResult(
    val ok: Boolean,
    val message: String,
    val intent: Intent? = null
)

internal data class SpotifyAuthResult(
    val handled: Boolean,
    val ok: Boolean,
    val message: String
)

internal data class SpotifyPlaybackResult(
    val ok: Boolean,
    val contentType: String,
    val query: String,
    val itemName: String? = null,
    val subtitle: String? = null,
    val itemUri: String? = null,
    val deviceName: String? = null,
    val error: String? = null
)

internal data class SpotifyPlaylistSummary(
    val name: String,
    val uri: String,
    val ownerName: String? = null,
    val trackCount: Int? = null
)

internal data class SpotifyPlaylistListResult(
    val ok: Boolean,
    val query: String? = null,
    val playlists: List<SpotifyPlaylistSummary> = emptyList(),
    val error: String? = null
)

internal data class SpotifyCreatePlaylistResult(
    val ok: Boolean,
    val name: String,
    val playlistUri: String? = null,
    val addedCount: Int = 0,
    val notFoundQueries: List<String> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyAddToPlaylistResult(
    val ok: Boolean,
    val playlistName: String? = null,
    val playlistUri: String? = null,
    val addedCount: Int = 0,
    val notFoundQueries: List<String> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyTrackSummary(
    val name: String,
    val uri: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Int = 0
)

internal data class SpotifyPlaybackStateResult(
    val ok: Boolean,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: String = "off",
    val progressMs: Int = 0,
    val volumePercent: Int = 0,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val track: SpotifyTrackSummary? = null,
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyPlaybackCommandResult(
    val ok: Boolean,
    val action: String,
    val deviceName: String? = null,
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyPlaybackOptionsResult(
    val ok: Boolean,
    val appliedOptions: List<String> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyPlaylistTracksResult(
    val ok: Boolean,
    val playlistName: String? = null,
    val playlistUri: String? = null,
    val tracks: List<SpotifyTrackSummary> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 25,
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyRemoveFromPlaylistResult(
    val ok: Boolean,
    val playlistName: String? = null,
    val playlistUri: String? = null,
    val removedCount: Int = 0,
    val notFoundQueries: List<String> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyPlaylistUpdateResult(
    val ok: Boolean,
    val playlistName: String? = null,
    val playlistUri: String? = null,
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyPlaylistReorderResult(
    val ok: Boolean,
    val playlistName: String? = null,
    val playlistUri: String? = null,
    val rangeStart: Int = 0,
    val insertBefore: Int = 0,
    val rangeLength: Int = 1,
    val snapshotId: String? = null,
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifySearchItem(
    val type: String,
    val name: String,
    val uri: String,
    val subtitle: String? = null,
    val ownerName: String? = null,
    val trackCount: Int? = null,
    val popularity: Int? = null
)

internal data class SpotifySearchResult(
    val ok: Boolean,
    val items: List<SpotifySearchItem> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyContainsResult(
    val uri: String,
    val saved: Boolean
)

internal data class SpotifyLibraryResult(
    val ok: Boolean,
    val items: List<SpotifyTrackSummary> = emptyList(),
    val changedCount: Int = 0,
    val containsResults: List<SpotifyContainsResult> = emptyList(),
    val notFoundQueries: List<String> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyTopItemsResult(
    val ok: Boolean,
    val timeRange: String,
    val items: List<SpotifySearchItem> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

internal data class SpotifyArtistTopTracksResult(
    val ok: Boolean,
    val artistName: String? = null,
    val artistUri: String? = null,
    val tracks: List<SpotifyTrackSummary> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

/** Pure JSON payload builders for the playlist endpoints — unit-testable with real org.json. */
internal object SpotifyPlaylistPayloads {
    fun createBody(name: String, isPublic: Boolean, description: String?): JSONObject {
        val body = JSONObject()
            .put("name", name)
            .put("public", isPublic)
        val trimmedDescription = description?.trim()
        if (!trimmedDescription.isNullOrBlank()) {
            body.put("description", trimmedDescription)
        }
        return body
    }

    fun addTracksBody(uris: List<String>): JSONObject {
        return JSONObject().put("uris", JSONArray().also { arr -> uris.forEach(arr::put) })
    }

    fun removeTracksBody(uris: List<String>): JSONObject {
        return JSONObject().put(
            "tracks",
            JSONArray().also { arr ->
                uris.forEach { uri -> arr.put(JSONObject().put("uri", uri)) }
            }
        )
    }

    fun updateDetailsBody(
        name: String?,
        description: String?,
        descriptionSet: Boolean,
        isPublic: Boolean?
    ): JSONObject {
        val body = JSONObject()
        name?.trim()?.takeIf { it.isNotBlank() }?.let { body.put("name", it) }
        if (descriptionSet) {
            body.put("description", description.orEmpty())
        }
        isPublic?.let { body.put("public", it) }
        return body
    }

    fun reorderBody(rangeStart: Int, insertBefore: Int, rangeLength: Int): JSONObject {
        return JSONObject()
            .put("range_start", rangeStart)
            .put("insert_before", insertBefore)
            .put("range_length", rangeLength)
    }

    fun chunkUris(uris: List<String>, size: Int): List<List<String>> {
        if (uris.isEmpty() || size <= 0) return emptyList()
        return uris.chunked(size)
    }
}

internal object SpotifyPlaylistEndpoints {
    fun createPlaylistUrl(apiBase: String): String = "${apiBase.trimEnd('/')}/me/playlists"

    fun addItemsUrl(apiBase: String, playlistId: String): String {
        return "${apiBase.trimEnd('/')}/playlists/${playlistId.trim()}/items"
    }

    fun itemsUrl(apiBase: String, playlistId: String): String {
        return "${apiBase.trimEnd('/')}/playlists/${playlistId.trim()}/items"
    }

    fun detailsUrl(apiBase: String, playlistId: String): String {
        return "${apiBase.trimEnd('/')}/playlists/${playlistId.trim()}"
    }

    fun updateItemsUrl(apiBase: String, playlistId: String): String {
        return "${apiBase.trimEnd('/')}/playlists/${playlistId.trim()}/tracks"
    }
}

internal object SpotifyLibraryPayloads {
    fun idsBody(ids: List<String>): JSONObject {
        return JSONObject().put("ids", JSONArray().also { arr -> ids.forEach(arr::put) })
    }

    fun chunkIds(ids: List<String>, size: Int): List<List<String>> {
        if (ids.isEmpty() || size <= 0) return emptyList()
        return ids.chunked(size)
    }
}

internal object SpotifyLibraryEndpoints {
    fun itemsUrl(apiBase: String, itemType: String): String {
        return "${apiBase.trimEnd('/')}/me/${pluralItemType(itemType)}"
    }

    fun containsUrl(apiBase: String, itemType: String): String {
        return "${itemsUrl(apiBase, itemType)}/contains"
    }

    private fun pluralItemType(itemType: String): String {
        return when (itemType.trim().lowercase()) {
            "album" -> "albums"
            else -> "tracks"
        }
    }
}

internal object SpotifyPlaybackValidation {
    private val reorderDestinations = setOf("top", "bottom")

    fun validateAction(action: String): String? {
        return if (action.trim().lowercase() in setOf("pause", "resume", "next", "previous")) {
            null
        } else {
            "Invalid Spotify playback action."
        }
    }

    fun validateVolume(volumePercent: Int): String? {
        return if (volumePercent in 0..100) null else "volume_percent must be between 0 and 100."
    }

    fun validateRepeatMode(repeatMode: String): String? {
        return if (repeatMode.trim().lowercase() in setOf("off", "track", "context")) {
            null
        } else {
            "repeat_mode must be off, track, or context."
        }
    }

    fun validateReorder(rangeStart: Int, insertBefore: Int, rangeLength: Int): String? {
        if (rangeStart < 0) return "range_start must be zero or greater."
        if (insertBefore < 0) return "insert_before must be zero or greater."
        if (rangeLength !in 1..100) return "range_length must be between 1 and 100."
        return null
    }

    fun validateReorderInputs(
        rangeStart: Int,
        insertBefore: Int,
        rangeLength: Int,
        trackQuery: String?,
        songUri: String?,
        beforeTrackQuery: String?,
        beforeSongUri: String?,
        destination: String?
    ): String? {
        if (rangeLength !in 1..100) return "range_length must be between 1 and 100."

        val hasDirectStart = rangeStart >= 0
        val hasTrackReference = !trackQuery.isNullOrBlank() || !songUri.isNullOrBlank()
        if (!hasDirectStart && !hasTrackReference) {
            return "Provide either range_start or a specific song reference to move."
        }

        val hasDirectInsert = insertBefore >= 0
        val hasDestinationReference = !beforeTrackQuery.isNullOrBlank() || !beforeSongUri.isNullOrBlank()
        val normalizedDestination = destination?.trim()?.lowercase()?.ifBlank { null }
        if (normalizedDestination != null && normalizedDestination !in reorderDestinations) {
            return "destination must be top or bottom."
        }
        if (!hasDirectInsert && !hasDestinationReference && normalizedDestination == null) {
            return "Provide either insert_before, destination, or a target song reference."
        }

        return null
    }
}

internal object SpotifyErrorPolicy {
    fun needsReconnect(statusCode: Int, errorMessage: String?): Boolean {
        if (statusCode == 401) return true
        if (statusCode != 403) return false

        val normalized = errorMessage.orEmpty().lowercase()
        return "scope" in normalized ||
            "permission" in normalized ||
            "permissions" in normalized
    }
}

internal object SpotifyScopePolicy {
    private const val PLAYLIST_MODIFY_PRIVATE = "playlist-modify-private"
    private const val PLAYLIST_MODIFY_PUBLIC = "playlist-modify-public"

    fun scopesToStore(
        responseScope: String,
        previousScope: String,
        preservePrevious: Boolean,
        requestedScope: String
    ): String {
        val returned = responseScope.trim()
        if (returned.isNotBlank()) return returned

        val previous = previousScope.trim()
        if (preservePrevious) return previous

        return requestedScope.trim()
    }

    fun canModifyPrivatePlaylists(scopes: String): Boolean {
        return PLAYLIST_MODIFY_PRIVATE in grantedScopeSet(scopes)
    }

    fun missingPlaylistModifyScope(scopes: String, isPublicPlaylist: Boolean): String? {
        val required = if (isPublicPlaylist) PLAYLIST_MODIFY_PUBLIC else PLAYLIST_MODIFY_PRIVATE
        return required.takeUnless { it in grantedScopeSet(scopes) }
    }

    fun missingScope(scopes: String, requiredScope: String): String? {
        return requiredScope.takeUnless { it in grantedScopeSet(scopes) }
    }

    fun missingAnyScope(scopes: String, requiredScopes: List<String>): String? {
        val granted = grantedScopeSet(scopes)
        return requiredScopes.firstOrNull { it !in granted }
    }

    private fun grantedScopeSet(scopes: String): Set<String> {
        return scopes
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}

internal object SpotifyTrackSearchSupport {
    const val MIN_ACCEPTABLE_SCORE = 430

    fun parseQuery(rawQuery: String): SpotifyParsedTrackQuery {
        val cleaned = rawQuery.trim().trim('"', '\'')
        val byMatch = Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleaned)
        if (byMatch != null) {
            return parsed(
                title = byMatch.groupValues[1],
                artist = byMatch.groupValues[2]
            )
        }

        val dashMatch = Regex("""^(.+?)\s+[-–—]\s+(.+)$""").find(cleaned)
        if (dashMatch != null) {
            return parsed(
                title = dashMatch.groupValues[1],
                artist = dashMatch.groupValues[2]
            )
        }

        return SpotifyParsedTrackQuery(
            title = cleaned,
            artist = null,
            searchQuery = cleaned
        )
    }

    fun matchScore(
        requestedTitle: String,
        requestedArtist: String?,
        candidateTitle: String,
        candidateArtists: List<String>
    ): Int {
        val titleScore = textMatchScore(requestedTitle, candidateTitle)
        if (titleScore == 0) return 0

        val artist = requestedArtist?.trim()?.takeIf { it.isNotBlank() }
        if (artist == null) return titleScore

        val artistScore = candidateArtists.maxOfOrNull { textMatchScore(artist, it) } ?: 0
        if (artistScore == 0) return 0

        return titleScore + artistScore
    }

    private fun parsed(title: String, artist: String): SpotifyParsedTrackQuery {
        val cleanTitle = title.trim().trim('"', '\'')
        val cleanArtist = artist.trim().trim('"', '\'')
        return SpotifyParsedTrackQuery(
            title = cleanTitle,
            artist = cleanArtist,
            searchQuery = "track:\"$cleanTitle\" artist:\"$cleanArtist\""
        )
    }

    private fun textMatchScore(expected: String, actual: String): Int {
        val normalizedExpected = normalize(expected)
        val normalizedActual = normalize(actual)
        if (normalizedExpected.isBlank() || normalizedActual.isBlank()) return 0
        if (normalizedExpected == normalizedActual) return 500
        if (normalizedActual.startsWith(normalizedExpected)) return 430
        if (normalizedActual.contains(normalizedExpected)) return 380

        val expectedTokens = normalizedExpected.split(' ').filter { it.isNotBlank() }
        val actualTokens = normalizedActual.split(' ').filter { it.isNotBlank() }.toSet()
        if (expectedTokens.isEmpty()) return 0
        val matches = expectedTokens.count { token ->
            actualTokens.any { actualToken ->
                actualToken == token || actualToken.startsWith(token) || token.startsWith(actualToken)
            }
        }
        if (matches == 0) return 0
        return ((matches.toDouble() / expectedTokens.size) * 320).toInt()
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}

internal data class SpotifyParsedTrackQuery(
    val title: String,
    val artist: String?,
    val searchQuery: String
)

internal object SpotifyUriSupport {
    fun canonicalUri(rawUri: String?, itemType: String, fallbackId: String?): String? {
        val uri = rawUri?.trim().orEmpty()
        if (uri.isNotBlank()) return uri

        val id = fallbackId?.trim().orEmpty()
        if (id.isBlank()) return null

        return "spotify:${itemType.trim().lowercase()}:$id"
    }
}

private data class SpotifyStoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val displayName: String? = null,
    val userId: String? = null,
    val scopes: String = ""
)

private data class SpotifyAccessTokenResult(
    val ok: Boolean,
    val accessToken: String? = null,
    val error: String? = null
)

private data class SpotifyHttpResult(
    val ok: Boolean,
    val code: Int = 0,
    val json: JSONObject? = null,
    val jsonArray: JSONArray? = null,
    val rawBody: String? = null,
    val error: String? = null
)

private data class SpotifyCatalogItem(
    val name: String,
    val subtitle: String?,
    val uri: String,
    val artistNames: List<String> = emptyList(),
    val popularity: Int = 0
)

private data class SpotifyItemResolution(
    val ok: Boolean,
    val item: SpotifyCatalogItem? = null,
    val error: String? = null
)

private data class SpotifyPlaylistItem(
    val name: String,
    val uri: String,
    val ownerName: String? = null,
    val trackCount: Int? = null,
    val isPublic: Boolean = false
)

private data class SpotifyPlaylistResolution(
    val ok: Boolean,
    val item: SpotifyPlaylistItem? = null,
    val error: String? = null
)

private data class SpotifyPlaylistPage(
    val items: List<SpotifyPlaylistItem>,
    val error: String? = null
)

private data class SpotifyAddTracksResult(
    val addedCount: Int,
    val error: String? = null,
    val needsReconnect: Boolean = false
)

private data class PlaylistTrackLoadResult(
    val ok: Boolean,
    val tracks: List<SpotifyTrackSummary> = emptyList(),
    val needsReconnect: Boolean = false,
    val error: String? = null
)

private data class PlaylistTrackReferenceResolution(
    val index: Int? = null,
    val error: String? = null
)

private data class PlaylistTrackCandidate(
    val index: Int,
    val score: Int
)

private data class SpotifyResolvedPlaylistAccess(
    val ok: Boolean,
    val accessToken: String? = null,
    val playlist: SpotifyPlaylistItem? = null,
    val needsReconnect: Boolean = false,
    val error: String? = null
)

private data class SpotifyDevice(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val isRestricted: Boolean
)

private data class SpotifyDeviceResolution(
    val ok: Boolean,
    val device: SpotifyDevice? = null,
    val error: String? = null
)
