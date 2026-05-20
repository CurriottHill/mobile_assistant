package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSnapshotSupportTest {

    @Test
    fun selectRecentNotifications_includesActiveNotificationsNotSeenSinceConnect() {
        val results = NotificationSnapshotSupport.selectRecentNotifications(
            buffered = listOf(
                record(
                    key = "wispr",
                    appLabel = "Wispr Flow",
                    packageName = "com.wispr.flow",
                    title = "Transcription ready",
                    text = "Meeting note captured",
                    postTimeMs = 1_000L
                )
            ),
            active = listOf(
                record(
                    key = "ntfy",
                    appLabel = "ntfy",
                    packageName = "io.heckel.ntfy",
                    title = "Server alert",
                    text = "CPU is hot",
                    postTimeMs = 4_000L
                ),
                record(
                    key = "wallet",
                    appLabel = "Google Wallet",
                    packageName = "com.google.android.apps.walletnfcrel",
                    title = "Pass update",
                    text = "Your boarding pass changed",
                    postTimeMs = 3_000L
                ),
                record(
                    key = "starling",
                    appLabel = "Starling Bank",
                    packageName = "com.starlingbank.android",
                    title = "Card payment",
                    text = "Coffee shop 4.50",
                    postTimeMs = 2_000L
                )
            ),
            appFilter = null,
            sinceMs = null,
            limit = 10,
            includeOngoing = false
        )

        assertEquals(
            listOf("ntfy", "Google Wallet", "Starling Bank", "Wispr Flow"),
            results.map { it.appLabel }
        )
    }

    @Test
    fun selectRecentNotifications_deduplicatesBufferedAndActiveCopies() {
        val duplicate = record(
            key = "same",
            appLabel = "Wispr Flow",
            packageName = "com.wispr.flow",
            title = "Transcription ready",
            text = "Meeting note captured",
            postTimeMs = 5_000L
        )

        val results = NotificationSnapshotSupport.selectRecentNotifications(
            buffered = listOf(duplicate),
            active = listOf(duplicate),
            appFilter = null,
            sinceMs = null,
            limit = 10,
            includeOngoing = false
        )

        assertEquals(1, results.size)
    }

    @Test
    fun selectRecentNotifications_excludesOngoingByDefault() {
        val results = NotificationSnapshotSupport.selectRecentNotifications(
            buffered = emptyList(),
            active = listOf(
                record(
                    key = "music",
                    appLabel = "Spotify",
                    packageName = "com.spotify.music",
                    title = "Now playing",
                    text = "Song",
                    postTimeMs = 2_000L,
                    isOngoing = true
                ),
                record(
                    key = "wallet",
                    appLabel = "Google Wallet",
                    packageName = "com.google.android.apps.walletnfcrel",
                    title = "Pass update",
                    text = "Gate changed",
                    postTimeMs = 1_000L
                )
            ),
            appFilter = null,
            sinceMs = null,
            limit = 10,
            includeOngoing = false
        )

        assertEquals(listOf("Google Wallet"), results.map { it.appLabel })
    }

    @Test
    fun selectRecentNotifications_filtersByAppLabelOrPackage() {
        val results = NotificationSnapshotSupport.selectRecentNotifications(
            buffered = emptyList(),
            active = listOf(
                record(
                    key = "wallet",
                    appLabel = "Google Wallet",
                    packageName = "com.google.android.apps.walletnfcrel",
                    title = "Pass update",
                    text = "Gate changed",
                    postTimeMs = 2_000L
                ),
                record(
                    key = "starling",
                    appLabel = "Starling Bank",
                    packageName = "com.starlingbank.android",
                    title = "Card payment",
                    text = "Coffee shop 4.50",
                    postTimeMs = 1_000L
                )
            ),
            appFilter = "starling",
            sinceMs = null,
            limit = 10,
            includeOngoing = false
        )

        assertEquals(1, results.size)
        assertTrue(results.single().packageName.contains("starling"))
    }

    private fun record(
        key: String,
        appLabel: String,
        packageName: String,
        title: String,
        text: String,
        postTimeMs: Long,
        isOngoing: Boolean = false
    ): NotificationSnapshotRecord {
        return NotificationSnapshotRecord(
            key = key,
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            postTimeMs = postTimeMs,
            category = null,
            isOngoing = isOngoing
        )
    }
}
