package com.example.mobile_assistant

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class PromptClockTest {
    @Test
    fun promptContextHeader_includesPhoneTimeZoneDetails() {
        val timeZone = TimeZone.getTimeZone("America/Los_Angeles")
        val now = GregorianCalendar(timeZone).apply {
            set(2026, Calendar.JULY, 4, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        val header = PromptClock.promptContextHeader(now = now, timeZone = timeZone)

        assertTrue(header.contains("Current date and time on the phone: 2026-07-04 12:00:00"))
        assertTrue(header.contains("Phone time zone: America/Los_Angeles"))
        assertTrue(header.contains("Pacific Daylight Time"))
        assertTrue(header.contains("UTC-07:00"))
        assertTrue(header.contains("Use this time zone for times and scheduling"))
    }
}
