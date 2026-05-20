package com.example.mobile_assistant

import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Returns recent notifications buffered by [AssistantNotificationListenerService]. If the
 * listener is not enabled, returns a structured error pointing at the enable screen.
 */
internal class NotificationToolService(private val context: android.content.Context) {

    fun execute(arguments: JSONObject): SharedToolExecutionResult {
        val listener = AssistantNotificationListenerService.instance
        if (listener == null) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_READ_NOTIFICATIONS,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_READ_NOTIFICATIONS)
                    .put("listener_enabled", false)
                    .put("settings_action", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .put("error", "Notification access is not enabled for this app."),
                chatResponse = "I need notification access first.",
                uiAction = PermissionUiActions.notificationAccess()
            )
        }

        val app = arguments.optString("app").trim().ifBlank { null }
        val limit = arguments.optInt("limit", 15)
        val includeOngoing = arguments.optBoolean("include_ongoing", false)
        val sinceMs = if (arguments.has("since_minutes")) {
            val minutes = arguments.optInt("since_minutes")
            if (minutes > 0) System.currentTimeMillis() - minutes * 60_000L else null
        } else {
            null
        }

        val records = listener.snapshot(app, sinceMs, limit, includeOngoing)
        val content = JSONObject()
            .put("ok", true)
            .put("tool", SharedToolSchemas.TOOL_READ_NOTIFICATIONS)
            .put("listener_enabled", true)
            .put("count", records.size)
            .also { c -> app?.let { c.put("app", it) } }
            .put("notifications", JSONArray().also { arr ->
                records.forEach { record ->
                    arr.put(
                        JSONObject()
                            .put("app", record.appLabel)
                            .put("package", record.packageName)
                            .put("title", record.title)
                            .put("text", record.text)
                            .put("post_time_ms", record.postTimeMs)
                            .also { o -> record.category?.let { o.put("category", it) } }
                    )
                }
            })

        val chatResponse = when {
            records.isEmpty() && app != null -> "You have no recent notifications from $app."
            records.isEmpty() -> "You have no recent notifications."
            else -> "You have ${records.size} recent notification${if (records.size == 1) "" else "s"}. " +
                records.take(4).joinToString(" ") { r ->
                    "${r.appLabel}: ${r.title.ifBlank { r.text }}."
                }
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_READ_NOTIFICATIONS,
            content = content,
            chatResponse = chatResponse
        )
    }
}
