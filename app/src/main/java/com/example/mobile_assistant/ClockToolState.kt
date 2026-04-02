package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class TrackedTimer(
    val id: String,
    val label: String? = null,
    val durationSeconds: Int,
    val startedAtEpochMs: Long,
    val expiresAtEpochMs: Long
) {
    fun remainingMs(nowEpochMs: Long): Long = (expiresAtEpochMs - nowEpochMs).coerceAtLeast(0L)

    fun isActive(nowEpochMs: Long): Boolean = expiresAtEpochMs > nowEpochMs

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("duration_seconds", durationSeconds)
            .put("started_at_epoch_ms", startedAtEpochMs)
            .put("expires_at_epoch_ms", expiresAtEpochMs)
            .also { json ->
                label?.let { json.put("label", it) }
            }
    }

    companion object {
        fun fromJson(json: JSONObject): TrackedTimer? {
            val id = json.optString("id").trim()
            val durationSeconds = json.optInt("duration_seconds", -1)
            val startedAtEpochMs = json.optLong("started_at_epoch_ms", -1L)
            val expiresAtEpochMs = json.optLong("expires_at_epoch_ms", -1L)
            if (id.isBlank() || durationSeconds <= 0 || startedAtEpochMs <= 0L || expiresAtEpochMs <= 0L) {
                return null
            }

            return TrackedTimer(
                id = id,
                label = json.optString("label").trim().ifBlank { null },
                durationSeconds = durationSeconds,
                startedAtEpochMs = startedAtEpochMs,
                expiresAtEpochMs = expiresAtEpochMs
            )
        }
    }
}

internal data class StopwatchState(
    val label: String? = null,
    val isRunning: Boolean = false,
    val accumulatedMs: Long = 0L,
    val startedAtEpochMs: Long? = null
) {
    fun elapsedMs(nowEpochMs: Long): Long {
        val runningDeltaMs = if (isRunning && startedAtEpochMs != null) {
            (nowEpochMs - startedAtEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return (accumulatedMs + runningDeltaMs).coerceAtLeast(0L)
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("is_running", isRunning)
            .put("accumulated_ms", accumulatedMs)
            .also { json ->
                label?.let { json.put("label", it) }
                startedAtEpochMs?.let { json.put("started_at_epoch_ms", it) }
            }
    }

    companion object {
        fun fromJson(json: JSONObject?): StopwatchState? {
            if (json == null) return null
            val isRunning = json.optBoolean("is_running", false)
            val accumulatedMs = json.optLong("accumulated_ms", 0L).coerceAtLeast(0L)
            val startedAtEpochMs = json.optLong("started_at_epoch_ms", 0L).takeIf { it > 0L }
            return StopwatchState(
                label = json.optString("label").trim().ifBlank { null },
                isRunning = isRunning,
                accumulatedMs = accumulatedMs,
                startedAtEpochMs = startedAtEpochMs
            )
        }
    }
}

internal object ClockToolStateSupport {
    fun trackedTimersFromJsonArray(jsonArray: JSONArray?): List<TrackedTimer> {
        if (jsonArray == null) return emptyList()
        return buildList {
            for (index in 0 until jsonArray.length()) {
                val timer = TrackedTimer.fromJson(jsonArray.optJSONObject(index) ?: continue) ?: continue
                add(timer)
            }
        }
    }

    fun trackedTimersToJsonArray(timers: List<TrackedTimer>): JSONArray {
        return JSONArray().also { array ->
            timers.forEach { timer -> array.put(timer.toJson()) }
        }
    }

    fun selectTrackedTimer(
        timers: List<TrackedTimer>,
        requestedLabel: String?,
        nowEpochMs: Long
    ): TrackedTimer? {
        val normalizedLabel = requestedLabel?.trim()?.lowercase(Locale.US).orEmpty()
        val filtered = if (normalizedLabel.isBlank()) {
            timers
        } else {
            timers.filter { timer ->
                val label = timer.label?.lowercase(Locale.US).orEmpty()
                label.contains(normalizedLabel)
            }
        }

        if (filtered.isEmpty()) return null

        val active = filtered.filter { it.isActive(nowEpochMs) }
        return active.maxByOrNull { it.startedAtEpochMs } ?: filtered.maxByOrNull { it.startedAtEpochMs }
    }

    fun activeTimerCount(timers: List<TrackedTimer>, nowEpochMs: Long): Int {
        return timers.count { it.isActive(nowEpochMs) }
    }

    fun formatDurationForSpeech(totalMs: Long): String {
        val safeMs = totalMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        val parts = mutableListOf<String>()
        if (hours > 0L) {
            parts += "$hours ${if (hours == 1L) "hour" else "hours"}"
        }
        if (minutes > 0L) {
            parts += "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        }
        if (seconds > 0L || parts.isEmpty()) {
            parts += "$seconds ${if (seconds == 1L) "second" else "seconds"}"
        }
        return parts.joinToString(" ")
    }

    fun parseWeekdays(rawDays: String?): ArrayList<Int> {
        val normalizedDays = rawDays
            ?.split(',')
            ?.mapNotNull { token ->
                when (token.trim().lowercase(Locale.US)) {
                    "sun", "sunday" -> java.util.Calendar.SUNDAY
                    "mon", "monday" -> java.util.Calendar.MONDAY
                    "tue", "tues", "tuesday" -> java.util.Calendar.TUESDAY
                    "wed", "wednesday" -> java.util.Calendar.WEDNESDAY
                    "thu", "thurs", "thursday" -> java.util.Calendar.THURSDAY
                    "fri", "friday" -> java.util.Calendar.FRIDAY
                    "sat", "saturday" -> java.util.Calendar.SATURDAY
                    else -> null
                }
            }
            ?.distinct()
            .orEmpty()

        return ArrayList(normalizedDays)
    }
}
