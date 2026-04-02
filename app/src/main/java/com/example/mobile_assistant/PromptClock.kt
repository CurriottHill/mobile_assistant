package com.example.mobile_assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object PromptClock {
    private const val PROMPT_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss z"

    fun promptDateTimeLine(now: Date = Date()): String {
        val formatter = SimpleDateFormat(PROMPT_TIMESTAMP_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return "Current date and time: ${formatter.format(now)}"
    }
}
