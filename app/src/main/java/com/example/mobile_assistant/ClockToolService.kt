package com.example.mobile_assistant

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal class ClockToolService(
    private val appContext: Context,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun executeTimer(arguments: JSONObject): SharedToolExecutionResult {
        return when (arguments.optString("action").trim().lowercase(Locale.US)) {
            "set" -> setTimer(arguments)
            "status" -> timerStatus(arguments)
            else -> invalidActionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_TIMER,
                supportedActions = "set or status"
            )
        }
    }

    fun executeAlarm(arguments: JSONObject): SharedToolExecutionResult {
        return when (arguments.optString("action").trim().lowercase(Locale.US)) {
            "set" -> setAlarm(arguments)
            "status" -> alarmStatus()
            "dismiss" -> dismissAlarm(arguments)
            "snooze" -> snoozeAlarm(arguments)
            else -> invalidActionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
                supportedActions = "set, status, dismiss, or snooze"
            )
        }
    }

    private fun setTimer(arguments: JSONObject): SharedToolExecutionResult {
        val durationSeconds = arguments.optInt("duration_seconds", 0)
        if (durationSeconds !in 1..MAX_TIMER_DURATION_SECONDS) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_TIMER,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CLOCK_TIMER)
                    .put("action", "set")
                    .put("error", "Timer duration must be between 1 and 86400 seconds."),
                chatResponse = "I need a timer length between 1 second and 24 hours."
            )
        }

        val label = arguments.optString("label").trim().ifBlank { null }
        val launch = launchClockIntent(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .also { intent ->
                    label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                }
        )

        if (!launch.ok) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_TIMER,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CLOCK_TIMER)
                    .put("action", "set")
                    .put("duration_seconds", durationSeconds)
                    .put("skip_ui_requested", true)
                    .also { content ->
                        label?.let { content.put("label", it) }
                        launch.error?.let { content.put("error", it) }
                    },
                chatResponse = launch.error ?: "I could not start a timer on this phone."
            )
        }

        val now = nowEpochMs()
        val timers = loadTrackedTimers().toMutableList()
        timers.add(
            0,
            TrackedTimer(
                id = UUID.randomUUID().toString(),
                label = label,
                durationSeconds = durationSeconds,
                startedAtEpochMs = now,
                expiresAtEpochMs = now + (durationSeconds * 1000L)
            )
        )
        saveTrackedTimers(trimTrackedTimers(timers))

        val response = buildString {
            append("Started a ")
            append(ClockToolStateSupport.formatDurationForSpeech(durationSeconds * 1000L))
            append(" timer")
            label?.let {
                append(" called ")
                append(it)
            }
            append(".")
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_CLOCK_TIMER,
            content = JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_CLOCK_TIMER)
                .put("action", "set")
                .put("duration_seconds", durationSeconds)
                .put("skip_ui_requested", true)
                .put("source", "alarm_clock_intent")
                .put("tracked_active_timers", ClockToolStateSupport.activeTimerCount(loadTrackedTimers(), now))
                .also { content ->
                    label?.let { content.put("label", it) }
                    launch.packageName?.let { content.put("handler_package", it) }
                    launch.handlerLabel?.let { content.put("handler_label", it) }
                },
            chatResponse = response
        )
    }

    private fun timerStatus(arguments: JSONObject): SharedToolExecutionResult {
        val requestedLabel = arguments.optString("label").trim().ifBlank { null }
        val now = nowEpochMs()
        val timers = loadTrackedTimers()
        val timer = ClockToolStateSupport.selectTrackedTimer(timers, requestedLabel, now)
        if (timer == null) {
            val response = if (requestedLabel == null) {
                "I am not tracking a timer from this assistant yet. Android does not expose live timer status through the clock intent API."
            } else {
                "I am not tracking a timer called $requestedLabel. Android does not expose live timer status through the clock intent API."
            }
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_TIMER,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CLOCK_TIMER)
                    .put("action", "status")
                    .put("error", "No matching tracked timer.")
                    .put("source", "assistant_tracking"),
                chatResponse = response
            )
        }

        val remainingMs = timer.remainingMs(now)
        val isActive = timer.isActive(now)
        val activeCount = ClockToolStateSupport.activeTimerCount(timers, now)
        val response = if (isActive) {
            buildString {
                append("Your ")
                append(timer.label ?: "tracked")
                append(" timer has about ")
                append(ClockToolStateSupport.formatDurationForSpeech(remainingMs))
                append(" left.")
                if (activeCount > 1 && requestedLabel == null) {
                    append(" I am tracking ")
                    append(activeCount)
                    append(" active timers.")
                }
            }
        } else {
            buildString {
                append("Your ")
                append(timer.label ?: "tracked")
                append(" timer has finished.")
            }
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_CLOCK_TIMER,
            content = JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_CLOCK_TIMER)
                .put("action", "status")
                .put("source", "assistant_tracking")
                .put("state", if (isActive) "running" else "finished")
                .put("duration_seconds", timer.durationSeconds)
                .put("started_at_epoch_ms", timer.startedAtEpochMs)
                .put("expires_at_epoch_ms", timer.expiresAtEpochMs)
                .put("remaining_seconds", ((remainingMs + 999L) / 1000L).coerceAtLeast(0L))
                .put("tracked_active_timers", activeCount)
                .also { content ->
                    timer.label?.let { content.put("label", it) }
                },
            chatResponse = response
        )
    }

    private fun setAlarm(arguments: JSONObject): SharedToolExecutionResult {
        val hour = arguments.optInt("hour", -1)
        val minute = arguments.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                    .put("action", "set")
                    .put("error", "Alarm time must include hour 0 to 23 and minute 0 to 59."),
                chatResponse = "I need a valid alarm time with hour 0 to 23 and minute 0 to 59."
            )
        }

        val label = arguments.optString("label").trim().ifBlank { null }
        val days = ClockToolStateSupport.parseWeekdays(arguments.optString("days").trim().ifBlank { null })
        val vibrate = when {
            arguments.has("vibrate") -> arguments.optBoolean("vibrate")
            else -> null
        }

        val launch = launchClockIntent(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .also { intent ->
                    label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    if (days.isNotEmpty()) {
                        intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
                    }
                    vibrate?.let { intent.putExtra(AlarmClock.EXTRA_VIBRATE, it) }
                }
        )

        if (!launch.ok) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                    .put("action", "set")
                    .put("hour", hour)
                    .put("minute", minute)
                    .put("skip_ui_requested", true)
                    .also { content ->
                        label?.let { content.put("label", it) }
                        launch.error?.let { content.put("error", it) }
                    },
                chatResponse = launch.error ?: "I could not set an alarm on this phone."
            )
        }

        val response = buildString {
            append("Set an alarm for ")
            append(formatClockTime(hour, minute))
            if (days.isNotEmpty()) {
                append(" on ")
                append(arguments.optString("days").trim())
            }
            label?.let {
                append(" called ")
                append(it)
            }
            append(".")
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
            content = JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                .put("action", "set")
                .put("hour", hour)
                .put("minute", minute)
                .put("skip_ui_requested", true)
                .put("source", "alarm_clock_intent")
                .also { content ->
                    label?.let { content.put("label", it) }
                    if (days.isNotEmpty()) {
                        content.put("days", arguments.optString("days").trim())
                    }
                    vibrate?.let { content.put("vibrate", it) }
                    launch.packageName?.let { content.put("handler_package", it) }
                    launch.handlerLabel?.let { content.put("handler_label", it) }
                },
            chatResponse = response
        )
    }

    private fun alarmStatus(): SharedToolExecutionResult {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val nextAlarm = alarmManager?.nextAlarmClock
        val formattedFallback = runCatching {
            Settings.System.getString(appContext.contentResolver, Settings.System.NEXT_ALARM_FORMATTED)
        }.getOrNull()?.trim().orEmpty()

        if (nextAlarm == null && formattedFallback.isBlank()) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                    .put("action", "status")
                    .put("state", "none")
                    .put("source", "alarm_manager"),
                chatResponse = "You do not appear to have a scheduled alarm."
            )
        }

        val triggerAtMs = nextAlarm?.triggerTime
        val spokenTime = triggerAtMs?.let(::formatDateTimeForSpeech)
            ?: formattedFallback.ifBlank { "an unknown time" }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
            content = JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                .put("action", "status")
                .put("state", "scheduled")
                .put("source", if (triggerAtMs != null) "alarm_manager" else "settings")
                .also { content ->
                    triggerAtMs?.let { content.put("trigger_at_epoch_ms", it) }
                    if (formattedFallback.isNotBlank()) {
                        content.put("formatted", formattedFallback)
                    }
                },
            chatResponse = "Your next alarm is set for $spokenTime."
        )
    }

    private fun dismissAlarm(arguments: JSONObject): SharedToolExecutionResult {
        val label = arguments.optString("label").trim().ifBlank { null }
        val hour = arguments.optInt("hour", -1).takeIf { it in 0..23 }
        val minute = arguments.optInt("minute", -1).takeIf { it in 0..59 }
        val dismissAll = arguments.optBoolean("dismiss_all", false)

        val searchMode = when {
            dismissAll -> AlarmClock.ALARM_SEARCH_MODE_ALL
            !label.isNullOrBlank() -> AlarmClock.ALARM_SEARCH_MODE_LABEL
            hour != null || minute != null -> AlarmClock.ALARM_SEARCH_MODE_TIME
            else -> AlarmClock.ALARM_SEARCH_MODE_NEXT
        }

        val launch = launchClockIntent(
            Intent(AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, searchMode)
                .also { intent ->
                    label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    hour?.let { intent.putExtra(AlarmClock.EXTRA_HOUR, it) }
                    minute?.let { intent.putExtra(AlarmClock.EXTRA_MINUTES, it) }
                }
        )

        if (!launch.ok) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                    .put("action", "dismiss")
                    .put("search_mode", searchMode)
                    .also { content ->
                        launch.error?.let { content.put("error", it) }
                    },
                chatResponse = launch.error ?: "I could not dismiss that alarm on this phone."
            )
        }

        val response = when {
            dismissAll -> "Asked the clock app to dismiss all alarms."
            !label.isNullOrBlank() -> "Asked the clock app to dismiss the alarm matching $label."
            hour != null || minute != null -> {
                val responseHour = hour ?: 0
                val responseMinute = minute ?: 0
                "Asked the clock app to dismiss the alarm closest to ${formatClockTime(responseHour, responseMinute)}."
            }
            else -> "Asked the clock app to dismiss the next alarm."
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
            content = JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                .put("action", "dismiss")
                .put("search_mode", searchMode)
                .put("source", "alarm_clock_intent")
                .also { content ->
                    label?.let { content.put("label", it) }
                    hour?.let { content.put("hour", it) }
                    minute?.let { content.put("minute", it) }
                    content.put("dismiss_all", dismissAll)
                },
            chatResponse = response
        )
    }

    private fun snoozeAlarm(arguments: JSONObject): SharedToolExecutionResult {
        val snoozeMinutes = arguments.optInt("snooze_minutes", -1).takeIf { it > 0 }
        val launch = launchClockIntent(
            Intent(AlarmClock.ACTION_SNOOZE_ALARM).also { intent ->
                snoozeMinutes?.let { intent.putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, it) }
            }
        )

        if (!launch.ok) {
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                    .put("action", "snooze")
                    .also { content ->
                        snoozeMinutes?.let { content.put("snooze_minutes", it) }
                        launch.error?.let { content.put("error", it) }
                    },
                chatResponse = launch.error ?: "I could not snooze the current alarm."
            )
        }

        val response = if (snoozeMinutes != null) {
            "Asked the clock app to snooze the current alarm for $snoozeMinutes minutes."
        } else {
            "Asked the clock app to snooze the current alarm."
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_CLOCK_ALARM,
            content = JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_CLOCK_ALARM)
                .put("action", "snooze")
                .put("source", "alarm_clock_intent")
                .also { content ->
                    snoozeMinutes?.let { content.put("snooze_minutes", it) }
                },
            chatResponse = response
        )
    }

    private fun invalidActionResult(
        toolName: String,
        supportedActions: String
    ): SharedToolExecutionResult {
        return SharedToolExecutionResult(
            toolName = toolName,
            content = JSONObject()
                .put("ok", false)
                .put("tool", toolName)
                .put("error", "Unsupported action."),
            chatResponse = "I need a supported action for $toolName, such as $supportedActions."
        )
    }

    private fun loadTrackedTimers(): List<TrackedTimer> {
        val raw = prefs.getString(PREF_TIMERS, null) ?: return emptyList()
        val parsedArray = runCatching { org.json.JSONArray(raw) }.getOrNull()
        return ClockToolStateSupport.trackedTimersFromJsonArray(parsedArray)
    }

    private fun saveTrackedTimers(timers: List<TrackedTimer>) {
        prefs.edit()
            .putString(PREF_TIMERS, ClockToolStateSupport.trackedTimersToJsonArray(timers).toString())
            .apply()
    }

    private fun trimTrackedTimers(timers: List<TrackedTimer>): List<TrackedTimer> {
        return timers
            .sortedByDescending { it.startedAtEpochMs }
            .take(MAX_TRACKED_TIMERS)
    }

    private fun launchClockIntent(intent: Intent): ClockIntentLaunchResult {
        val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val packageManager = appContext.packageManager
        val resolveInfo = resolveActivity(packageManager, launchIntent)
            ?: return ClockIntentLaunchResult(
                ok = false,
                error = "I could not find a clock app on this phone that supports that action."
            )

        return try {
            appContext.startActivity(launchIntent)
            ClockIntentLaunchResult(
                ok = true,
                packageName = resolveInfo.activityInfo?.packageName,
                handlerLabel = resolveInfo.loadLabel(packageManager).toString().trim().ifBlank { null }
            )
        } catch (_: ActivityNotFoundException) {
            ClockIntentLaunchResult(
                ok = false,
                error = "I could not find a clock app on this phone that supports that action."
            )
        } catch (e: Exception) {
            ClockIntentLaunchResult(
                ok = false,
                error = e.message ?: "Clock action failed."
            )
        }
    }

    private fun resolveActivity(
        packageManager: PackageManager,
        intent: Intent
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveActivity(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    private fun formatClockTime(hour: Int, minute: Int): String {
        val normalizedHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val minuteText = minute.toString().padStart(2, '0')
        val meridiem = if (hour >= 12) "PM" else "AM"
        return "$normalizedHour $minuteText $meridiem"
    }

    private fun formatDateTimeForSpeech(epochMs: Long): String {
        val dateText = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(epochMs))
        val timeText = SimpleDateFormat("h mm a", Locale.getDefault()).format(Date(epochMs))
        return "$dateText at $timeText"
    }

    private data class ClockIntentLaunchResult(
        val ok: Boolean,
        val packageName: String? = null,
        val handlerLabel: String? = null,
        val error: String? = null
    )

    companion object {
        private const val PREFS_NAME = "clock_tool_state"
        private const val PREF_TIMERS = "tracked_timers"
        private const val MAX_TRACKED_TIMERS = 10
        private const val MAX_TIMER_DURATION_SECONDS = 86_400
    }
}
