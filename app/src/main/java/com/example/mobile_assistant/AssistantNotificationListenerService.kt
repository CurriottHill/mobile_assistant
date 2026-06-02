package com.example.mobile_assistant

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Buffers recent notifications so the assistant can answer "any important Slack messages?"
 * without pulling down the shade. Singleton mirrors [AssistantAccessibilityService].
 */
internal class AssistantNotificationListenerService : NotificationListenerService() {

    private val buffer = ArrayDeque<NotificationSnapshotRecord>()

    override fun onListenerConnected() {
        instance = this
        runCatching {
            val existingKeys = synchronized(buffer) { buffer.mapNotNull { it.key }.toSet() }
            val seedRecords = activeNotifications.orEmpty()
                .mapNotNull(::recordFromStatusBarNotification)
                .filter { it.key == null || it.key !in existingKeys }
            synchronized(buffer) {
                seedRecords.forEach { buffer.addLast(it) }
                while (buffer.size > MAX_BUFFER) buffer.removeFirst()
            }
        }
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val record = sbn?.let(::recordFromStatusBarNotification) ?: return

        synchronized(buffer) {
            buffer.addLast(record)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        synchronized(buffer) {
            buffer.removeAll { it.key == key }
        }
    }

    private fun resolveAppLabel(pkg: String): String {
        if (pkg.isBlank()) return pkg
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** Newest-first snapshot, optionally filtered by app substring / recency. */
    internal fun snapshot(
        appFilter: String?,
        sinceMs: Long?,
        limit: Int,
        includeOngoing: Boolean
    ): List<NotificationSnapshotRecord> {
        val buffered = synchronized(buffer) { buffer.toList() }
        val active = runCatching {
            activeNotifications.orEmpty().mapNotNull(::recordFromStatusBarNotification)
        }.getOrDefault(emptyList())
        return NotificationSnapshotSupport.selectRecentNotifications(
            buffered = buffered,
            active = active,
            appFilter = appFilter,
            sinceMs = sinceMs,
            limit = limit.coerceIn(1, MAX_BUFFER),
            includeOngoing = includeOngoing
        )
    }

    private fun recordFromStatusBarNotification(sbn: StatusBarNotification): NotificationSnapshotRecord? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: Bundle.EMPTY
        val title = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?.joinToString(" ")
            .orEmpty()
        val body = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
            textLines,
            extractMessagingBody(extras)
        )
        if (title.isBlank() && body.isBlank()) return null

        val pkg = sbn.packageName.orEmpty()
        val flags = notification.flags
        return NotificationSnapshotRecord(
            key = sbn.key,
            packageName = pkg,
            appLabel = resolveAppLabel(pkg),
            title = title,
            text = body,
            postTimeMs = sbn.postTime,
            category = notification.category,
            isOngoing = flags and Notification.FLAG_ONGOING_EVENT != 0
        )
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstNotNullOfOrNull { candidate ->
            candidate?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()
    }

    private fun extractMessagingBody(extras: Bundle): String {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return ""
        return Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            .mapNotNull { message -> message.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(" ")
    }

    companion object {
        private const val MAX_BUFFER = 100

        @Volatile
        var instance: AssistantNotificationListenerService? = null
            private set
    }
}
