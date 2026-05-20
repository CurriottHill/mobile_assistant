package com.example.mobile_assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Device + media controls that need no permission (volume, media keys, flashlight, status)
 * except set_brightness which needs the special WRITE_SETTINGS access and reports a
 * structured error when it is not granted.
 */
internal class DeviceMediaToolService(private val context: Context) {

    @Volatile
    private var torchOn = false

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // --- set_volume -------------------------------------------------------

    fun executeSetVolume(arguments: JSONObject): SharedToolExecutionResult {
        val streamName = arguments.optString("stream").trim().lowercase().ifBlank { "media" }
        val stream = STREAMS[streamName]
            ?: return error(SharedToolSchemas.TOOL_SET_VOLUME, "Unknown stream '$streamName'.", "I do not know that audio stream.")
        return runCatching {
            val am = audioManager
            if (arguments.has("mute")) {
                val mute = arguments.optBoolean("mute")
                am.adjustStreamVolume(
                    stream,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            val applied = JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_SET_VOLUME)
                .put("stream", streamName)
            var spoken = "Updated the $streamName volume."
            if (arguments.has("level")) {
                val percent = arguments.optInt("level").coerceIn(0, 100)
                val max = am.getStreamMaxVolume(stream)
                val scaled = Math.round(percent / 100f * max)
                am.setStreamVolume(stream, scaled, AudioManager.FLAG_SHOW_UI)
                applied.put("level", percent)
                spoken = "Set the $streamName volume to $percent percent."
            }
            if (arguments.has("mute")) {
                applied.put("mute", arguments.optBoolean("mute"))
                if (!arguments.has("level")) {
                    spoken = if (arguments.optBoolean("mute")) "Muted the $streamName." else "Unmuted the $streamName."
                }
            }
            SharedToolExecutionResult(SharedToolSchemas.TOOL_SET_VOLUME, applied, spoken)
        }.getOrElse { e ->
            error(SharedToolSchemas.TOOL_SET_VOLUME, e.message ?: "Failed to set volume.", "I could not change the volume just now.")
        }
    }

    // --- media_control ----------------------------------------------------

    fun executeMediaControl(arguments: JSONObject): SharedToolExecutionResult {
        val action = arguments.optString("action").trim().lowercase()
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return error(
                SharedToolSchemas.TOOL_MEDIA_CONTROL,
                "Unknown media action '$action'.",
                "I do not know that media action."
            )
        }
        return runCatching {
            val am = audioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_MEDIA_CONTROL,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_MEDIA_CONTROL)
                    .put("action", action),
                chatResponse = "Sent the $action media command."
            )
        }.getOrElse { e ->
            error(SharedToolSchemas.TOOL_MEDIA_CONTROL, e.message ?: "Failed to control media.", "I could not control media playback just now.")
        }
    }

    // --- toggle_flashlight ------------------------------------------------

    fun executeToggleFlashlight(arguments: JSONObject): SharedToolExecutionResult {
        val state = arguments.optString("state").trim().lowercase().ifBlank { "toggle" }
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return error(
                SharedToolSchemas.TOOL_TOGGLE_FLASHLIGHT,
                "No camera flash available.",
                "This device has no flashlight I can control."
            )
            val enable = when (state) {
                "on" -> true
                "off" -> false
                else -> !torchOn
            }
            cm.setTorchMode(cameraId, enable)
            torchOn = enable
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_TOGGLE_FLASHLIGHT,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_TOGGLE_FLASHLIGHT)
                    .put("torch_on", enable),
                chatResponse = if (enable) "Flashlight is on." else "Flashlight is off."
            )
        }.getOrElse { e ->
            error(SharedToolSchemas.TOOL_TOGGLE_FLASHLIGHT, e.message ?: "Failed to toggle flashlight.", "I could not control the flashlight just now.")
        }
    }

    // --- get_device_status ------------------------------------------------

    fun executeGetDeviceStatus(): SharedToolExecutionResult {
        return runCatching {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val statusExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = statusExtra == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusExtra == BatteryManager.BATTERY_STATUS_FULL

            val connectivity = runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                when {
                    caps == null -> "none"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "other"
                }
            }.getOrDefault("unknown")

            val ringer = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            val brightness = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
            }.getOrDefault(-1)

            val content = JSONObject()
                .put("ok", true)
                .put("tool", SharedToolSchemas.TOOL_GET_DEVICE_STATUS)
                .put("battery_percent", batteryPct)
                .put("charging", charging)
                .put("connectivity", connectivity)
                .put("ringer_mode", ringer)
                .put("brightness_percent", brightness)

            val spoken = buildString {
                append("Battery is ")
                append(if (batteryPct >= 0) "$batteryPct percent" else "unknown")
                append(if (charging) " and charging" else "")
                append(". Connectivity is ")
                append(connectivity)
                append(". Ringer is ")
                append(ringer)
                append(".")
            }
            SharedToolExecutionResult(SharedToolSchemas.TOOL_GET_DEVICE_STATUS, content, spoken)
        }.getOrElse { e ->
            error(SharedToolSchemas.TOOL_GET_DEVICE_STATUS, e.message ?: "Failed to read device status.", "I could not read the device status just now.")
        }
    }

    private fun error(tool: String, err: String, chatResponse: String) = SharedToolExecutionResult(
        toolName = tool,
        content = JSONObject().put("ok", false).put("tool", tool).put("error", err),
        chatResponse = chatResponse
    )

    private fun readSystemBrightnessRaw(): Int =
        runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)

    private fun readSystemBrightnessMode(): Int =
        runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrDefault(-1)

    private companion object {
        private val STREAMS = mapOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "call" to AudioManager.STREAM_VOICE_CALL
        )
    }
}
