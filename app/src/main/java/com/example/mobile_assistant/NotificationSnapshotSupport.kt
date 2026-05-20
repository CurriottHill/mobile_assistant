package com.example.mobile_assistant

import java.util.Locale

internal data class NotificationSnapshotRecord(
    val key: String?,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
    val category: String?,
    val isOngoing: Boolean
)

internal object NotificationSnapshotSupport {

    fun selectRecentNotifications(
        buffered: List<NotificationSnapshotRecord>,
        active: List<NotificationSnapshotRecord>,
        appFilter: String?,
        sinceMs: Long?,
        limit: Int,
        includeOngoing: Boolean
    ): List<NotificationSnapshotRecord> {
        val normalizedFilter = appFilter?.trim()?.lowercase(Locale.US)?.ifBlank { null }
        val boundedLimit = limit.coerceAtLeast(1)

        return (active.asSequence().map { RankedRecord(record = it, priority = 1) } +
            buffered.asSequence().map { RankedRecord(record = it, priority = 0) })
            .filter { ranked ->
                val record = ranked.record
                (normalizedFilter == null ||
                    record.appLabel.lowercase(Locale.US).contains(normalizedFilter) ||
                    record.packageName.lowercase(Locale.US).contains(normalizedFilter)) &&
                    (sinceMs == null || record.postTimeMs >= sinceMs) &&
                    (includeOngoing || !record.isOngoing)
            }
            .sortedWith(
                compareByDescending<RankedRecord> { it.record.postTimeMs }
                    .thenByDescending { it.priority }
            )
            .map { it.record }
            .distinctBy(::dedupeKey)
            .take(boundedLimit)
            .toList()
    }

    private fun dedupeKey(record: NotificationSnapshotRecord): String {
        return record.key?.takeIf { it.isNotBlank() }
            ?: listOf(
                record.packageName,
                record.title,
                record.text,
                record.postTimeMs.toString()
            ).joinToString("|")
    }

    private data class RankedRecord(
        val record: NotificationSnapshotRecord,
        val priority: Int
    )
}
