package com.example.mobile_assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object PromptClock {
    private const val PROMPT_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"

    fun promptContextHeader(
        now: Date = Date(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        return buildString {
            appendLine(promptDateTimeLine(now, timeZone))
            append(promptTimeZoneLine(now, timeZone))
        }
    }

    fun promptDateTimeLine(
        now: Date = Date(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val formatter = SimpleDateFormat(PROMPT_TIMESTAMP_PATTERN, Locale.US).apply {
            this.timeZone = timeZone
        }
        return "Current date and time on the phone: ${formatter.format(now)}"
    }

    fun promptTimeZoneLine(
        now: Date = Date(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val isDaylightSavings = timeZone.inDaylightTime(now)
        val timeZoneName = timeZone.getDisplayName(isDaylightSavings, TimeZone.LONG, Locale.US)
        val offsetMillis = timeZone.getOffset(now.time)
        return "Phone time zone: ${timeZone.id} ($timeZoneName, ${formatUtcOffset(offsetMillis)}). Use this time zone for times and scheduling unless the user explicitly asks for a different one."
    }

    private fun formatUtcOffset(offsetMillis: Int): String {
        val totalMinutes = offsetMillis / 60_000
        val sign = if (totalMinutes >= 0) "+" else "-"
        val absoluteMinutes = kotlin.math.abs(totalMinutes)
        val hours = absoluteMinutes / 60
        val minutes = absoluteMinutes % 60
        return String.format(Locale.US, "UTC%s%02d:%02d", sign, hours, minutes)
    }
}
