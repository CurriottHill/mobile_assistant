package com.example.mobile_assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only access to the user's connected Google account (Gmail + Calendar) over the REST
 * APIs, so the assistant can answer things like "any important emails today?" without ever
 * opening an app. Authorization uses Google Identity Services instead of custom OAuth
 * redirects, which Google blocks for Android custom schemes.
 *
 * NOTE: tokens are stored in [Context.MODE_PRIVATE] SharedPreferences to match the existing
 * Spotify store and avoid a new dependency. Hardening to EncryptedSharedPreferences
 * (androidx.security-crypto) is a tracked follow-up and only touches this file.
 */
internal class GoogleAccountService(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val authorizationClient = Identity.getAuthorizationClient(appContext)

    private fun isConfigured(): Boolean = true

    fun connectionStatus(): GoogleConnectionStatus {
        val tokens = readTokens()
        if (tokens.accessToken.isBlank()) {
            return GoogleConnectionStatus(
                isConfigured = true,
                isConnected = false,
                statusText = "Google account is not connected."
            )
        }
        val label = tokens.email?.takeIf { it.isNotBlank() } ?: "Google account"
        return GoogleConnectionStatus(
            isConfigured = true,
            isConnected = true,
            statusText = "Connected to $label."
        )
    }

    fun createLoginIntent(): GoogleLoginLaunchResult {
        return GoogleLoginLaunchResult(
            ok = true,
            message = "Connect your Google account.",
            intent = Intent(appContext, GoogleAuthorizationActivity::class.java)
        )
    }

    /** Reads recent Gmail messages so the model can summarize what matters. */
    suspend fun fetchRecentEmails(
        query: String?,
        maxResults: Int,
        sinceHours: Int?
    ): GmailFetchResult = withContext(Dispatchers.IO) {
        val tokenResult = requireAccessToken()
        if (!tokenResult.ok || tokenResult.accessToken == null) {
            return@withContext GmailFetchResult(
                ok = false,
                error = tokenResult.error ?: "Google account is not connected.",
                needsConnect = isConfigured()
            )
        }
        val accessToken = tokenResult.accessToken
        val limit = maxResults.coerceIn(1, 25)
        val gmailQuery = GoogleQueryBuilder.gmailQuery(query, sinceHours, System.currentTimeMillis())

        val listResponse = sendRequest(
            Request.Builder()
                .url("$GMAIL_API_BASE/users/me/messages?maxResults=$limit&q=${Uri.encode(gmailQuery)}")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        if (!listResponse.ok || listResponse.json == null) {
            if (listResponse.code == 401) clearStoredTokens()
            return@withContext GmailFetchResult(
                ok = false,
                error = listResponse.error ?: "Could not read your Gmail.",
                needsConnect = listResponse.code == 401
            )
        }

        val ids = listResponse.json.optJSONArray("messages")
        val emails = mutableListOf<GmailMessageSummary>()
        if (ids != null) {
            for (index in 0 until minOf(ids.length(), limit)) {
                val id = ids.optJSONObject(index)?.optString("id")?.trim().orEmpty()
                if (id.isBlank()) continue
                val detail = sendRequest(
                    Request.Builder()
                        .url("$GMAIL_API_BASE/users/me/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .get()
                        .build()
                ).json ?: continue
                val headers = detail.optJSONObject("payload")?.optJSONArray("headers")
                var from = ""
                var subject = ""
                var date = ""
                if (headers != null) {
                    for (h in 0 until headers.length()) {
                        val header = headers.optJSONObject(h) ?: continue
                        when (header.optString("name").lowercase(Locale.US)) {
                            "from" -> from = header.optString("value").trim()
                            "subject" -> subject = header.optString("value").trim()
                            "date" -> date = header.optString("value").trim()
                        }
                    }
                }
                emails.add(
                    GmailMessageSummary(
                        id = id,
                        threadId = detail.optString("threadId").trim(),
                        from = from.ifBlank { "Unknown sender" },
                        subject = subject.ifBlank { "(no subject)" },
                        snippet = detail.optString("snippet").trim(),
                        received = date
                    )
                )
            }
        }

        GmailFetchResult(ok = true, query = gmailQuery, emails = emails)
    }

    /** Reads a specific Gmail message after [fetchRecentEmails] has returned its id. */
    suspend fun fetchEmailMessage(messageId: String): GmailMessageDetailResult = withContext(Dispatchers.IO) {
        val id = messageId.trim()
        if (id.isBlank()) {
            return@withContext GmailMessageDetailResult(
                ok = false,
                error = "Missing Gmail message id."
            )
        }

        val tokenResult = requireAccessToken()
        if (!tokenResult.ok || tokenResult.accessToken == null) {
            return@withContext GmailMessageDetailResult(
                ok = false,
                error = tokenResult.error ?: "Google account is not connected.",
                needsConnect = isConfigured()
            )
        }
        val accessToken = tokenResult.accessToken

        val response = sendRequest(
            Request.Builder()
                .url("$GMAIL_API_BASE/users/me/messages/${Uri.encode(id)}?format=full")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        if (!response.ok || response.json == null) {
            if (response.code == 401) clearStoredTokens()
            return@withContext GmailMessageDetailResult(
                ok = false,
                error = response.error ?: "Could not read that Gmail message.",
                needsConnect = response.code == 401
            )
        }

        GmailMessageDetailResult(
            ok = true,
            message = parseGmailMessageDetail(response.json)
        )
    }

    /**
     * Sends an email via the Gmail API (users.messages.send). Requires the gmail.send scope;
     * if the stored token only covers readonly scopes, the first call here triggers a
     * re-consent prompt through [requireAccessToken].
     */
    suspend fun sendEmail(
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        body: String
    ): GoogleSendEmailResult = withContext(Dispatchers.IO) {
        val cleanTo = to.map(String::trim).filter { it.isNotEmpty() }
        if (cleanTo.isEmpty()) {
            return@withContext GoogleSendEmailResult(ok = false, error = "Missing recipient.")
        }

        val tokenResult = requireAccessToken()
        if (!tokenResult.ok || tokenResult.accessToken == null) {
            return@withContext GoogleSendEmailResult(
                ok = false,
                error = tokenResult.error ?: "Google account is not connected.",
                needsConnect = true
            )
        }
        val accessToken = tokenResult.accessToken

        val raw = encodeRfc2822Message(
            to = cleanTo,
            cc = cc.map(String::trim).filter { it.isNotEmpty() },
            bcc = bcc.map(String::trim).filter { it.isNotEmpty() },
            subject = subject,
            body = body
        )

        val payload = JSONObject().put("raw", raw).toString()
        val response = sendRequest(
            Request.Builder()
                .url("$GMAIL_API_BASE/users/me/messages/send")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
        if (!response.ok || response.json == null) {
            val needsConsent = response.code == 401 ||
                response.code == 403 ||
                response.error?.contains("insufficient", ignoreCase = true) == true ||
                response.error?.contains("scope", ignoreCase = true) == true
            if (needsConsent) {
                clearStoredTokens()
                launchConsentActivity()
                return@withContext GoogleSendEmailResult(
                    ok = false,
                    needsConnect = true,
                    error = "Gmail needs send permission. A consent screen is opening — tap Allow, then ask me to send it again."
                )
            }
            return@withContext GoogleSendEmailResult(
                ok = false,
                error = response.error ?: "Gmail API rejected the send request."
            )
        }

        val messageId = response.json.optString("id").trim().ifBlank { null }
        // Double-check: re-fetch the message to confirm it landed in Sent.
        val verified = messageId?.let { verifySent(accessToken, it) } ?: false
        GoogleSendEmailResult(
            ok = true,
            messageId = messageId,
            verifiedSent = verified
        )
    }

    private suspend fun verifySent(accessToken: String, messageId: String): Boolean {
        val response = sendRequest(
            Request.Builder()
                .url("$GMAIL_API_BASE/users/me/messages/${Uri.encode(messageId)}?format=minimal")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        if (!response.ok || response.json == null) return false
        val labels = response.json.optJSONArray("labelIds") ?: return false
        for (i in 0 until labels.length()) {
            if (labels.optString(i) == "SENT") return true
        }
        return false
    }

    private fun launchConsentActivity() {
        runCatching {
            appContext.startActivity(
                Intent(appContext, GoogleAuthorizationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun encodeRfc2822Message(
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        body: String
    ): String {
        val headers = buildString {
            append("To: ").append(to.joinToString(", ")).append("\r\n")
            if (cc.isNotEmpty()) append("Cc: ").append(cc.joinToString(", ")).append("\r\n")
            if (bcc.isNotEmpty()) append("Bcc: ").append(bcc.joinToString(", ")).append("\r\n")
            append("Subject: ").append(encodeMimeHeader(subject)).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            append("Content-Transfer-Encoding: 8bit\r\n")
            append("\r\n")
        }
        val mime = headers + body
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mime.toByteArray(Charsets.UTF_8))
    }

    private fun encodeMimeHeader(value: String): String {
        // Pure-ASCII subjects can ride as-is; otherwise B-encode to keep non-ASCII intact.
        val asciiOnly = value.all { it.code in 0x20..0x7E }
        if (asciiOnly) return value
        val base64 = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        return "=?UTF-8?B?$base64?="
    }

    /** Reads upcoming Google Calendar events for the requested window. */
    suspend fun fetchAgenda(
        range: String,
        date: String? = null,
        dateRange: String? = null
    ): CalendarFetchResult = withContext(Dispatchers.IO) {
        val tokenResult = requireAccessToken()
        if (!tokenResult.ok || tokenResult.accessToken == null) {
            return@withContext CalendarFetchResult(
                ok = false,
                error = tokenResult.error ?: "Google account is not connected.",
                needsConnect = isConfigured()
            )
        }
        val accessToken = tokenResult.accessToken
        val window = GoogleQueryBuilder.calendarWindow(
            range, date, dateRange, System.currentTimeMillis()
        )

        val response = sendRequest(
            Request.Builder()
                .url(
                    "$CALENDAR_API_BASE/calendars/primary/events" +
                        "?singleEvents=true&orderBy=startTime&maxResults=25" +
                        "&timeMin=${Uri.encode(window.first)}&timeMax=${Uri.encode(window.second)}"
                )
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        if (!response.ok || response.json == null) {
            if (response.code == 401) clearStoredTokens()
            return@withContext CalendarFetchResult(
                ok = false,
                error = response.error ?: "Could not read your Google Calendar.",
                needsConnect = response.code == 401
            )
        }

        val items = response.json.optJSONArray("items")
        val events = mutableListOf<CalendarEventSummary>()
        if (items != null) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val start = item.optJSONObject("start")
                val end = item.optJSONObject("end")
                events.add(
                    CalendarEventSummary(
                        title = item.optString("summary").trim().ifBlank { "(no title)" },
                        start = start?.optString("dateTime")?.trim()?.ifBlank { null }
                            ?: start?.optString("date")?.trim().orEmpty(),
                        end = end?.optString("dateTime")?.trim()?.ifBlank { null }
                            ?: end?.optString("date")?.trim().orEmpty(),
                        location = item.optString("location").trim().ifBlank { null },
                        id = item.optString("id").trim().ifBlank { null },
                        htmlLink = item.optString("htmlLink").trim().ifBlank { null }
                    )
                )
            }
        }

        CalendarFetchResult(ok = true, range = range, events = events)
    }

    private fun parseGmailMessageDetail(detail: JSONObject): GmailMessageDetail {
        val headers = detail.optJSONObject("payload")?.optJSONArray("headers")
        var from = ""
        var to = ""
        var subject = ""
        var date = ""
        if (headers != null) {
            for (h in 0 until headers.length()) {
                val header = headers.optJSONObject(h) ?: continue
                when (header.optString("name").lowercase(Locale.US)) {
                    "from" -> from = header.optString("value").trim()
                    "to" -> to = header.optString("value").trim()
                    "subject" -> subject = header.optString("value").trim()
                    "date" -> date = header.optString("value").trim()
                }
            }
        }

        val body = GmailBodyTextExtractor.extract(detail.optJSONObject("payload"))
        return GmailMessageDetail(
            id = detail.optString("id").trim(),
            threadId = detail.optString("threadId").trim(),
            from = from.ifBlank { "Unknown sender" },
            to = to,
            subject = subject.ifBlank { "(no subject)" },
            snippet = detail.optString("snippet").trim(),
            received = date,
            body = body
        )
    }

    private fun readTokens(): GoogleStoredTokens {
        return GoogleStoredTokens(
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty(),
            expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L),
            email = prefs.getString(KEY_EMAIL, null)
        )
    }

    private suspend fun refreshStoredProfileIfPossible() {
        val tokenResult = requireAccessToken(forceRefresh = false)
        val accessToken = tokenResult.accessToken ?: return
        val response = withContext(Dispatchers.IO) {
            sendRequest(
                Request.Builder()
                    .url("$GMAIL_API_BASE/users/me/profile")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
        }
        val email = response.json?.optString("emailAddress")?.trim()?.ifBlank { null } ?: return
        prefs.edit().putString(KEY_EMAIL, email).apply()
    }

    private suspend fun requireAccessToken(forceRefresh: Boolean = false): GoogleAccessTokenResult {
        val stored = readTokens()
        val now = System.currentTimeMillis()
        if (!forceRefresh &&
            stored.accessToken.isNotBlank() &&
            stored.expiresAtMs > now + TOKEN_REFRESH_SKEW_MS
        ) {
            return GoogleAccessTokenResult(ok = true, accessToken = stored.accessToken)
        }

        val authorization = runCatching {
            authorizationClient.authorize(authorizationRequest()).await()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return GoogleAccessTokenResult(
                ok = false,
                error = error.message ?: "Google account authorization failed."
            )
        }
        if (authorization.hasResolution()) {
            return GoogleAccessTokenResult(
                ok = false,
                error = "Google account is not connected yet."
            )
        }

        val accessToken = authorization.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) {
            return GoogleAccessTokenResult(
                ok = false,
                error = "Google did not return an access token."
            )
        }

        saveAuthorizationToken(accessToken)
        return GoogleAccessTokenResult(ok = true, accessToken = accessToken)
    }

    private fun clearStoredTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .remove(KEY_EMAIL)
            .apply()
    }

    fun authorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(GOOGLE_SCOPES.map(::Scope))
            .build()
    }

    suspend fun saveAuthorizationResult(result: AuthorizationResult): GoogleAuthResult {
        val accessToken = result.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) {
            return GoogleAuthResult(
                handled = true,
                ok = false,
                message = "Google did not return an access token."
            )
        }

        saveAuthorizationToken(accessToken)
        refreshStoredProfileIfPossible()
        return GoogleAuthResult(
            handled = true,
            ok = true,
            message = "Google account connected."
        )
    }

    private fun saveAuthorizationToken(accessToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT_MS, System.currentTimeMillis() + ACCESS_TOKEN_TTL_MS)
            .apply()
    }

    private fun sendRequest(request: Request): GoogleHttpResult {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = body.takeIf { it.isNotBlank() }?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                }
                if (response.isSuccessful) {
                    GoogleHttpResult(ok = true, json = json, code = response.code)
                } else {
                    GoogleHttpResult(
                        ok = false,
                        json = json,
                        code = response.code,
                        error = extractGoogleError(body, response.code)
                    )
                }
            }
        } catch (e: Exception) {
            GoogleHttpResult(ok = false, error = e.message ?: "Google request failed.")
        }
    }

    private fun extractGoogleError(responseBody: String, statusCode: Int): String {
        val json = runCatching { JSONObject(responseBody) }.getOrNull()
        val errorObj = json?.optJSONObject("error")
        val message = errorObj?.optString("message")?.trim()
            ?: json?.optString("error_description")?.trim()
            ?: json?.optString("error")?.trim()
        val best = message?.takeIf { it.isNotBlank() }
            ?: responseBody.replace(Regex("\\s+"), " ").trim().take(200)
        return when (statusCode) {
            401 -> "Google login expired. Connect your Google account again."
            403 -> best.ifBlank { "Google denied that request." }
            else -> best.ifBlank { "Google request failed with HTTP $statusCode." }
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            continuation.resume(result)
        }
        addOnFailureListener { error ->
            continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }

    companion object {
        private const val PREFS_NAME = "google_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val KEY_EMAIL = "email"
        private const val GMAIL_API_BASE = "https://gmail.googleapis.com/gmail/v1"
        private const val CALENDAR_API_BASE = "https://www.googleapis.com/calendar/v3"
        private const val TOKEN_REFRESH_SKEW_MS = 60_000L
        private const val ACCESS_TOKEN_TTL_MS = 55 * 60 * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val GOOGLE_SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.send",
            "https://www.googleapis.com/auth/calendar.readonly"
        )
    }
}

/**
 * Pure builders for Gmail search queries and Calendar time windows. No Android dependencies
 * so they are unit-testable with real org.json like [AppOpener]'s matching logic.
 */
internal object GoogleQueryBuilder {

    fun gmailQuery(rawQuery: String?, sinceHours: Int?, nowMillis: Long): String {
        val base = rawQuery?.trim().orEmpty().ifBlank { "is:important newer_than:1d" }
        val hours = sinceHours?.takeIf { it > 0 } ?: return base
        val afterSeconds = ((nowMillis - hours * 3_600_000L) / 1000L).coerceAtLeast(0L)
        return if (base.contains("after:")) base else "$base after:$afterSeconds"
    }

    /** Returns RFC-3339 UTC [timeMin, timeMax]. Day windows use UTC day boundaries. */
    fun calendarWindow(range: String, nowMillis: Long): Pair<String, String> {
        val dayMs = 86_400_000L
        val startOfUtcDay = nowMillis - (nowMillis % dayMs)
        return when (range.trim().lowercase(Locale.US)) {
            "tomorrow" -> rfc3339(startOfUtcDay + dayMs) to rfc3339(startOfUtcDay + 2 * dayMs)
            "next_24h" -> rfc3339(nowMillis) to rfc3339(nowMillis + dayMs)
            else -> rfc3339(startOfUtcDay) to rfc3339(startOfUtcDay + dayMs)
        }
    }

    /**
     * Window selection with explicit overrides. An explicit [dateRange] (YYYY-MM-DD..YYYY-MM-DD,
     * inclusive start day to exclusive end day) wins, then an explicit [date] (single
     * YYYY-MM-DD day), otherwise falls back to the relative [range] enum.
     */
    fun calendarWindow(
        range: String,
        date: String?,
        dateRange: String?,
        nowMillis: Long
    ): Pair<String, String> {
        val dayMs = 86_400_000L
        dateRange?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            val parts = raw.split("..").map { it.trim() }
            if (parts.size == 2) {
                val startMs = parseUtcDayStart(parts[0])
                val endMs = parseUtcDayStart(parts[1])
                if (startMs != null && endMs != null) {
                    return rfc3339(startMs) to rfc3339(endMs.coerceAtLeast(startMs + dayMs))
                }
            }
        }
        date?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            parseUtcDayStart(raw)?.let { startMs ->
                return rfc3339(startMs) to rfc3339(startMs + dayMs)
            }
        }
        return calendarWindow(range, nowMillis)
    }

    private fun parseUtcDayStart(day: String): Long? {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.isLenient = false
            sdf.parse(day.trim())?.time
        }.getOrNull()
    }

    fun rfc3339(millis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(millis))
    }
}

internal data class GoogleConnectionStatus(
    val isConfigured: Boolean,
    val isConnected: Boolean,
    val statusText: String
)

internal data class GoogleLoginLaunchResult(
    val ok: Boolean,
    val message: String,
    val intent: Intent? = null
)

internal data class GoogleAuthResult(
    val handled: Boolean,
    val ok: Boolean,
    val message: String
)

internal data class GmailMessageSummary(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val received: String
)

internal data class GmailFetchResult(
    val ok: Boolean,
    val query: String? = null,
    val emails: List<GmailMessageSummary> = emptyList(),
    val needsConnect: Boolean = false,
    val error: String? = null
)

internal data class GmailMessageDetail(
    val id: String,
    val threadId: String,
    val from: String,
    val to: String,
    val subject: String,
    val snippet: String,
    val received: String,
    val body: String
)

internal data class GmailMessageDetailResult(
    val ok: Boolean,
    val message: GmailMessageDetail? = null,
    val needsConnect: Boolean = false,
    val error: String? = null
)

internal data class CalendarEventSummary(
    val title: String,
    val start: String,
    val end: String,
    val location: String? = null,
    val id: String? = null,
    val htmlLink: String? = null
)

internal data class GoogleSendEmailResult(
    val ok: Boolean,
    val messageId: String? = null,
    val verifiedSent: Boolean = false,
    val needsConnect: Boolean = false,
    val error: String? = null
)

internal data class CalendarFetchResult(
    val ok: Boolean,
    val range: String? = null,
    val events: List<CalendarEventSummary> = emptyList(),
    val needsConnect: Boolean = false,
    val error: String? = null
)

private data class GoogleStoredTokens(
    val accessToken: String,
    val expiresAtMs: Long,
    val email: String? = null
)

private data class GoogleAccessTokenResult(
    val ok: Boolean,
    val accessToken: String? = null,
    val error: String? = null
)

private data class GoogleHttpResult(
    val ok: Boolean,
    val code: Int = 0,
    val json: JSONObject? = null,
    val error: String? = null
)

internal object GmailBodyTextExtractor {
    fun extract(payload: JSONObject?): String {
        if (payload == null) return ""
        val plainParts = mutableListOf<String>()
        val htmlParts = mutableListOf<String>()
        collectTextParts(payload, plainParts, htmlParts)
        val parts = plainParts.ifEmpty { htmlParts.map(::htmlToReadableText) }
        return parts.joinToString("\n\n")
            .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(12_000)
    }

    private fun collectTextParts(
        part: JSONObject,
        plainOut: MutableList<String>,
        htmlOut: MutableList<String>
    ) {
        val mimeType = part.optString("mimeType").lowercase(Locale.US)
        if (mimeType == "text/plain") {
            decodeBodyData(part)?.takeIf { it.isNotBlank() }?.let(plainOut::add)
        } else if (mimeType == "text/html") {
            decodeBodyData(part)?.takeIf { it.isNotBlank() }?.let(htmlOut::add)
        }

        val children = part.optJSONArray("parts") ?: return
        for (index in 0 until children.length()) {
            val child = children.optJSONObject(index) ?: continue
            collectTextParts(child, plainOut, htmlOut)
        }
    }

    private fun decodeBodyData(part: JSONObject): String? {
        val data = part.optJSONObject("body")
            ?.optString("data")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val padded = data + "=".repeat((4 - data.length % 4) % 4)
        return runCatching {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun htmlToReadableText(html: String): String {
        return html
            .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
