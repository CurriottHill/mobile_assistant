package com.example.mobile_assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
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

        return SpotifyConnectionStatus(
            isConfigured = true,
            isConnected = true,
            statusText = "Connected to $label."
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

    suspend fun playPlaylist(query: String): SpotifyPlaybackResult {
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

    private fun isConfigured(): Boolean {
        return BuildConfig.SPOTIFY_CLIENT_ID.trim().isNotBlank() && redirectUri.isNotBlank()
    }

    private fun readTokens(): SpotifyStoredTokens {
        return SpotifyStoredTokens(
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty(),
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null).orEmpty(),
            expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L),
            displayName = prefs.getString(KEY_DISPLAY_NAME, null),
            userId = prefs.getString(KEY_USER_ID, null)
        )
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
                error = "Spotify is not connected yet. Open the app home page and tap Connect Spotify."
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
            .apply()
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

        val response = sendJsonRequestBlocking(
            Request.Builder()
                .url(
                    "$SPOTIFY_API_BASE/search?q=${Uri.encode(query)}&type=track&limit=1"
                )
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val item = response.json
            ?.optJSONObject("tracks")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
            ?.toTrackItem()

        return if (item != null) {
            SpotifyItemResolution(ok = true, item = item)
        } else {
            SpotifyItemResolution(ok = false, error = response.error ?: "No matching Spotify song was found.")
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
        val uri = optString("uri").trim()
        if (uri.isBlank()) return null
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
            uri = uri
        )
    }

    private fun JSONObject.toAlbumItem(): SpotifyCatalogItem? {
        val uri = optString("uri").trim()
        if (uri.isBlank()) return null
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
            uri = uri
        )
    }

    private fun JSONObject.toPlaylistItem(): SpotifyPlaylistItem? {
        val uri = optString("uri").trim()
        if (uri.isBlank()) return null
        val name = optString("name").trim().ifBlank { "Spotify playlist" }
        val owner = optJSONObject("owner")
        return SpotifyPlaylistItem(
            name = name,
            uri = uri,
            ownerName = owner?.optString("display_name")?.trim()?.ifBlank {
                owner.optString("id").trim().ifBlank { null }
            },
            trackCount = optJSONObject("tracks")?.optInt("total")
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
                if (response.isSuccessful) {
                    SpotifyHttpResult(ok = true, json = json, code = response.code)
                } else {
                    SpotifyHttpResult(
                        ok = false,
                        json = json,
                        code = response.code,
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
        private const val PREFS_NAME = "spotify_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_USER_ID = "user_id"
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
            "playlist-read-collaborative"
        )
    }
}

internal data class SpotifyConnectionStatus(
    val isConfigured: Boolean,
    val isConnected: Boolean,
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

private data class SpotifyStoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val displayName: String? = null,
    val userId: String? = null
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
    val error: String? = null
)

private data class SpotifyCatalogItem(
    val name: String,
    val subtitle: String?,
    val uri: String
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
    val trackCount: Int? = null
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
