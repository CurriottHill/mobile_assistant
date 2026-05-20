package com.example.mobile_assistant

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Handles email composing via intents and calendar operations via ContentResolver (no UI).
 * Email requires confirm_send=true to send. Calendar create/edit write directly to the
 * calendar database; delete opens for review only.
 */
internal class CalendarToolService(
    private val context: Context,
    private val tapSendButton: suspend (List<ScreenReader.SendButtonSelector>) -> Boolean,
    private val sendEmailViaApi: suspend (EmailDraft) -> GoogleSendEmailResult
) {
    @Volatile
    private var pendingEmailDraft: EmailDraft? = null

    suspend fun executeComposeEmail(arguments: JSONObject): JSONObject {
        val to = readStringList(arguments, "to")
        val subject = arguments.optString("subject").trim()
        val body = arguments.optString("body").trim()
        val cc = readStringList(arguments, "cc")
        val bcc = readStringList(arguments, "bcc")
        val confirmSend = arguments.optBoolean("confirm_send", false) ||
            arguments.optBoolean("sent", false) ||
            arguments.optBoolean("send", false)
        val hasDraftPayload = to.isNotEmpty() ||
            cc.isNotEmpty() ||
            bcc.isNotEmpty() ||
            subject.isNotBlank() ||
            body.isNotBlank()

        if (AgentToolExecutorSupport.shouldReusePendingEmailDraft(
                hasDraftPayload = hasDraftPayload,
                confirmSend = confirmSend,
                hasPendingDraft = pendingEmailDraft != null
            )
        ) {
            return sendPendingEmailDraft(inferredConfirmSend = !confirmSend)
        }
        if (to.isEmpty()) {
            return errorContent(AgentTooling.TOOL_COMPOSE_EMAIL, "Missing recipient (to).")
        }

        val draft = EmailDraft(
            to = to,
            cc = cc,
            bcc = bcc,
            subject = subject,
            body = body
        )
        pendingEmailDraft = draft

        val content = draftContent(draft, confirmSend = false)
        if (confirmSend) {
            content.put("ignored_initial_confirm_send", true)
        }
        return runCatching {
            context.startActivity(draft.toIntent())
            content
                .put("ok", true)
                .put("opened", true)
                .put("draft_ready", true)
                .put("sent", false)
                .put(
                    "note",
                    "Draft opened for review. Read the subject and body back to the user, then wait for confirmation before sending."
                )
            content
        }.getOrElse { e ->
            errorContent(AgentTooling.TOOL_COMPOSE_EMAIL, e.message ?: "Failed to open email composer.")
        }
    }

    suspend fun executeCreateEvent(arguments: JSONObject): JSONObject {
        val title = arguments.optString("title").trim()
        if (title.isBlank()) {
            return errorContent(AgentTooling.TOOL_CALENDAR_CREATE_EVENT, "Missing event title.")
        }
        if (!hasWriteCalendarPermission()) {
            return errorContent(AgentTooling.TOOL_CALENDAR_CREATE_EVENT, "Calendar write permission not granted.")
        }
        val startMs = parseDateTime(arguments.optString("start"))
            ?: return errorContent(
                AgentTooling.TOOL_CALENDAR_CREATE_EVENT,
                "Could not parse start time. Use ISO-8601 (e.g. 2026-05-20T14:00) or epoch milliseconds."
            )
        val allDay = arguments.optBoolean("all_day", false)
        val endMs = parseDateTime(arguments.optString("end"))
            ?: (startMs + 60 * 60 * 1000L)
        val calendarId = defaultCalendarId()

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            arguments.optString("location").trim().takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            arguments.optString("description").trim().takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }

        val content = JSONObject()
            .put("tool", AgentTooling.TOOL_CALENDAR_CREATE_EVENT)
            .put("title", title)
            .put("start_ms", startMs)
            .put("end_ms", endMs)
            .put("all_day", allDay)
        arguments.optString("location").trim().takeIf { it.isNotBlank() }?.let { content.put("location", it) }
        arguments.optString("description").trim().takeIf { it.isNotBlank() }?.let { content.put("description", it) }
        val attendees = readStringList(arguments, "attendees")
        if (attendees.isNotEmpty()) content.put("attendees", toJsonArray(attendees))

        return runCatching {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return errorContent(AgentTooling.TOOL_CALENDAR_CREATE_EVENT, "ContentResolver insert returned null.")
            val eventId = uri.lastPathSegment?.toLongOrNull()
            if (eventId != null && attendees.isNotEmpty()) {
                insertAttendees(eventId, attendees)
            }
            content.put("ok", true).put("saved", true)
            if (eventId != null) content.put("local_event_id", eventId)
            content
        }.getOrElse { e ->
            errorContent(AgentTooling.TOOL_CALENDAR_CREATE_EVENT, e.message ?: "Failed to create event.")
        }
    }

    suspend fun executeEditEvent(arguments: JSONObject): JSONObject {
        val target = parseExistingEventTarget(arguments)
            ?: return errorContent(
                AgentTooling.TOOL_CALENDAR_EDIT_EVENT,
                "Missing event target. Pass event_link from check_calendar, or a numeric local_event_id."
            )

        val requestedChanges = buildRequestedEventChanges(arguments)
        val content = JSONObject()
            .put("tool", AgentTooling.TOOL_CALENDAR_EDIT_EVENT)
            .put("requested_changes", requestedChanges)
        target.describeInto(content)

        if (target is ExistingEventTarget.LocalEvent) {
            if (!hasWriteCalendarPermission()) {
                return errorContent(AgentTooling.TOOL_CALENDAR_EDIT_EVENT, "Calendar write permission not granted.")
            }
            return runCatching {
                val values = buildEventUpdateValues(arguments)
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.localEventId)
                val updated = context.contentResolver.update(uri, values, null, null)
                val attendees = readStringList(arguments, "attendees")
                if (updated > 0 && attendees.isNotEmpty()) {
                    insertAttendees(target.localEventId, attendees)
                }
                content.put("ok", true).put("saved", updated > 0)
                if (updated == 0) content.put("error", "No rows updated — event may not exist.")
                content
            }.getOrElse { e ->
                errorContent(AgentTooling.TOOL_CALENDAR_EDIT_EVENT, e.message ?: "Failed to update event.")
            }
        }

        // Web-linked events: open for review, user saves manually
        return runCatching {
            context.startActivity(buildEditIntent(target, arguments))
            content.put("ok", true)
                .put("opened", true)
                .put("saved", false)
                .put("note", "Web calendar event opened for editing. Apply and save changes manually.")
            content
        }.getOrElse { e ->
            errorContent(AgentTooling.TOOL_CALENDAR_EDIT_EVENT, e.message ?: "Failed to open the event for editing.")
        }
    }

    suspend fun executeDeleteEvent(arguments: JSONObject): JSONObject {
        val target = parseExistingEventTarget(arguments)
            ?: return errorContent(
                AgentTooling.TOOL_CALENDAR_DELETE_EVENT,
                "Missing event target. Pass event_link from check_calendar, or a numeric local_event_id."
            )
        val content = JSONObject()
            .put("tool", AgentTooling.TOOL_CALENDAR_DELETE_EVENT)
            .put("deleted", false)
        target.describeInto(content)

        return runCatching {
            context.startActivity(buildViewIntent(target))
            content.put("ok", true)
                .put("opened", true)
                .put(
                    "note",
                    "Event opened for deletion review. Read the target event back to the user, then wait for a later explicit request before using phone controls to delete it."
                )
            content
        }.getOrElse { e ->
            errorContent(AgentTooling.TOOL_CALENDAR_DELETE_EVENT, e.message ?: "Failed to open the event for deletion.")
        }
    }

    private suspend fun pollTap(selectors: List<ScreenReader.SendButtonSelector>): Boolean {
        // Up to ~9s: Gmail compose can take a moment to inflate after a cold start.
        repeat(15) {
            delay(600L)
            if (tapSendButton(selectors)) return true
        }
        return false
    }

    private suspend fun sendPendingEmailDraft(inferredConfirmSend: Boolean = false): JSONObject {
        val draft = pendingEmailDraft
            ?: return errorContent(
                AgentTooling.TOOL_COMPOSE_EMAIL,
                "No pending email draft is available to send."
            )
        val content = draftContent(draft, confirmSend = true)
            .put("draft_reused", true)
        if (inferredConfirmSend) {
            content.put("inferred_confirm_send", true)
        }

        val apiResult = runCatching { sendEmailViaApi(draft) }.getOrNull()
        if (apiResult?.ok == true) {
            pendingEmailDraft = null
            return content
                .put("ok", true)
                .put("opened", false)
                .put("draft_ready", false)
                .put("used_existing_composer", false)
                .put("sent", true)
                .put("verified_in_sent_folder", apiResult.verifiedSent)
                .put("send_path", "gmail_api")
                .also { c -> apiResult.messageId?.let { c.put("gmail_message_id", it) } }
        }
        if (apiResult != null) {
            content.put("api_send_error", apiResult.error ?: "Gmail API send unavailable.")
        }
        if (apiResult?.needsConnect == true) {
            // Consent screen is opening; do not fall back to more UI actions.
            return content
                .put("ok", false)
                .put("opened", false)
                .put("draft_ready", true)
                .put("sent", false)
                .put("needs_reconnect", true)
                .put("send_path", "awaiting_consent")
        }

        if (pollTap(GMAIL_SEND_SELECTORS)) {
            pendingEmailDraft = null
            return content
                .put("ok", true)
                .put("opened", false)
                .put("draft_ready", false)
                .put("used_existing_composer", true)
                .put("sent", true)
                .put("send_path", "accessibility_tap")
        }

        return runCatching {
            context.startActivity(draft.toIntent())
            content
                .put("ok", true)
                .put("opened", true)
                .put("used_existing_composer", false)
            val sent = pollTap(GMAIL_SEND_SELECTORS)
            content.put("sent", sent)
            content.put("draft_ready", !sent)
            if (sent) {
                pendingEmailDraft = null
            } else {
                content.put("error", "Reopened the draft but could not tap Send automatically.")
            }
            content
        }.getOrElse { e ->
            errorContent(AgentTooling.TOOL_COMPOSE_EMAIL, e.message ?: "Failed to send pending email draft.")
        }
    }

    private fun hasWriteCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun defaultCalendarId(): Long {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null, "${CalendarContract.Calendars._ID} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 1L
    }

    private fun insertAttendees(eventId: Long, emails: List<String>) {
        emails.forEach { email ->
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
            }
            runCatching { context.contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, values) }
        }
    }

    private fun buildEventUpdateValues(arguments: JSONObject): ContentValues = ContentValues().apply {
        arguments.optString("title").trim().takeIf { it.isNotBlank() }
            ?.let { put(CalendarContract.Events.TITLE, it) }
        parseDateTime(arguments.optString("start"))
            ?.let { put(CalendarContract.Events.DTSTART, it) }
        parseDateTime(arguments.optString("end"))
            ?.let { put(CalendarContract.Events.DTEND, it) }
        if (arguments.has("all_day")) {
            put(CalendarContract.Events.ALL_DAY, if (arguments.optBoolean("all_day")) 1 else 0)
        }
        arguments.optString("location").trim().takeIf { it.isNotBlank() }
            ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        if (arguments.has("description")) {
            put(CalendarContract.Events.DESCRIPTION, arguments.optString("description").trim())
        }
    }

    private fun parseDateTime(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        value.toLongOrNull()?.let { return it }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                if (!pattern.contains("XXX")) sdf.timeZone = TimeZone.getDefault()
                sdf.isLenient = false
                return sdf.parse(value)?.time
            }
        }
        return null
    }

    private fun readStringList(arguments: JSONObject, key: String): List<String> {
        val array = arguments.optJSONArray(key)
        if (array != null) {
            return (0 until array.length()).mapNotNull { array.optString(it).trim().ifBlank { null } }
        }
        return arguments.optString(key).trim().ifBlank { null }?.let { listOf(it) } ?: emptyList()
    }

    private fun parseExistingEventTarget(arguments: JSONObject): ExistingEventTarget? {
        arguments.optString("event_link").trim().ifBlank { null }?.let {
            return ExistingEventTarget.WebLink(it)
        }
        return arguments.optString("local_event_id").trim()
            .toLongOrNull()
            ?.let { ExistingEventTarget.LocalEvent(it) }
    }

    private fun buildEditIntent(target: ExistingEventTarget, arguments: JSONObject): Intent {
        return when (target) {
            is ExistingEventTarget.LocalEvent -> Intent(Intent.ACTION_EDIT).apply {
                data = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.localEventId)
                putEventMutationExtras(arguments)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            is ExistingEventTarget.WebLink -> Intent(Intent.ACTION_VIEW, Uri.parse(target.eventLink)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun buildViewIntent(target: ExistingEventTarget): Intent {
        return when (target) {
            is ExistingEventTarget.LocalEvent -> Intent(Intent.ACTION_VIEW).apply {
                data = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.localEventId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            is ExistingEventTarget.WebLink -> Intent(Intent.ACTION_VIEW, Uri.parse(target.eventLink)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun Intent.putEventMutationExtras(arguments: JSONObject) {
        arguments.optString("title").trim().takeIf { it.isNotBlank() }
            ?.let { putExtra(CalendarContract.Events.TITLE, it) }
        parseDateTime(arguments.optString("start"))
            ?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        parseDateTime(arguments.optString("end"))
            ?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        if (arguments.has("all_day")) {
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, arguments.optBoolean("all_day"))
        }
        arguments.optString("location").trim().takeIf { it.isNotBlank() }
            ?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        if (arguments.has("description")) {
            putExtra(CalendarContract.Events.DESCRIPTION, arguments.optString("description").trim())
        }
        val attendees = readStringList(arguments, "attendees")
        if (attendees.isNotEmpty()) {
            putExtra(Intent.EXTRA_EMAIL, attendees.joinToString(","))
        }
    }

    private fun buildRequestedEventChanges(arguments: JSONObject): JSONObject = JSONObject().also { changes ->
        arguments.optString("title").trim().takeIf { it.isNotBlank() }?.let { changes.put("title", it) }
        arguments.optString("start").trim().takeIf { it.isNotBlank() }?.let { changes.put("start", it) }
        arguments.optString("end").trim().takeIf { it.isNotBlank() }?.let { changes.put("end", it) }
        if (arguments.has("all_day")) changes.put("all_day", arguments.optBoolean("all_day"))
        arguments.optString("location").trim().takeIf { it.isNotBlank() }?.let { changes.put("location", it) }
        if (arguments.has("description")) changes.put("description", arguments.optString("description").trim())
        val attendees = readStringList(arguments, "attendees")
        if (attendees.isNotEmpty()) changes.put("attendees", toJsonArray(attendees))
    }

    private fun draftContent(draft: EmailDraft, confirmSend: Boolean): JSONObject = JSONObject()
        .put("tool", AgentTooling.TOOL_COMPOSE_EMAIL)
        .put("to", toJsonArray(draft.to))
        .put("cc", toJsonArray(draft.cc))
        .put("bcc", toJsonArray(draft.bcc))
        .put("subject", draft.subject)
        .put("body", draft.body)
        .put("confirm_send", confirmSend)

    private fun toJsonArray(values: List<String>): JSONArray = JSONArray().also { array ->
        values.forEach(array::put)
    }

    private fun errorContent(tool: String, error: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("tool", tool)
        .put("opened", false)
        .put("error", error)

    internal data class EmailDraft(
        val to: List<String>,
        val cc: List<String>,
        val bcc: List<String>,
        val subject: String,
        val body: String
    ) {
        fun toIntent(): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
            if (cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, cc.toTypedArray())
            if (bcc.isNotEmpty()) putExtra(Intent.EXTRA_BCC, bcc.toTypedArray())
            if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private sealed interface ExistingEventTarget {
        fun describeInto(content: JSONObject)

        data class WebLink(val eventLink: String) : ExistingEventTarget {
            override fun describeInto(content: JSONObject) {
                content.put("event_link", eventLink)
            }
        }

        data class LocalEvent(val localEventId: Long) : ExistingEventTarget {
            override fun describeInto(content: JSONObject) {
                content.put("local_event_id", localEventId)
            }
        }
    }

    private companion object {
        private val GMAIL_SEND_SELECTORS = listOf(
            ScreenReader.SendButtonSelector(viewId = "com.google.android.gm:id/send"),
            ScreenReader.SendButtonSelector(descEquals = "Send"),
            ScreenReader.SendButtonSelector(textEquals = "Send")
        )
    }
}
