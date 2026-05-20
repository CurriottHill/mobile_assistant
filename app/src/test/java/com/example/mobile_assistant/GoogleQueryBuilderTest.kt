package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleQueryBuilderTest {

    // 2023-11-14T22:13:20Z
    private val now = 1_700_000_000_000L

    @Test
    fun gmailQueryDefaultsToImportantAndRecent() {
        assertEquals(
            "is:important newer_than:1d",
            GoogleQueryBuilder.gmailQuery(null, null, now)
        )
        assertEquals(
            "is:important newer_than:1d",
            GoogleQueryBuilder.gmailQuery("   ", null, now)
        )
    }

    @Test
    fun gmailQueryKeepsCustomQuery() {
        assertEquals(
            "from:boss@work.com is:unread",
            GoogleQueryBuilder.gmailQuery("from:boss@work.com is:unread", null, now)
        )
    }

    @Test
    fun gmailQueryAppendsAfterForSinceHours() {
        // now - 6h, in epoch seconds
        assertEquals(
            "is:important newer_than:1d after:1699978400",
            GoogleQueryBuilder.gmailQuery(null, 6, now)
        )
    }

    @Test
    fun gmailQueryDoesNotDoubleAddAfter() {
        assertEquals(
            "after:123",
            GoogleQueryBuilder.gmailQuery("after:123", 6, now)
        )
    }

    @Test
    fun calendarTodayUsesUtcDayBoundaries() {
        val window = GoogleQueryBuilder.calendarWindow("today", now)
        assertEquals("2023-11-14T00:00:00Z", window.first)
        assertEquals("2023-11-15T00:00:00Z", window.second)
    }

    @Test
    fun calendarTomorrowShiftsByOneDay() {
        val window = GoogleQueryBuilder.calendarWindow("tomorrow", now)
        assertEquals("2023-11-15T00:00:00Z", window.first)
        assertEquals("2023-11-16T00:00:00Z", window.second)
    }

    @Test
    fun calendarNext24hStartsFromNow() {
        val window = GoogleQueryBuilder.calendarWindow("next_24h", now)
        assertEquals("2023-11-14T22:13:20Z", window.first)
        assertEquals("2023-11-15T22:13:20Z", window.second)
    }

    @Test
    fun calendarUnknownRangeFallsBackToToday() {
        val window = GoogleQueryBuilder.calendarWindow("whenever", now)
        assertEquals("2023-11-14T00:00:00Z", window.first)
    }
}
