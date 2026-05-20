package com.example.mobile_assistant

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.Settings

internal object PermissionUiActions {
    fun appPermission(context: Context, permission: String): AssistantUiAction {
        val label = when (permission) {
            Manifest.permission.RECORD_AUDIO -> "Open Microphone Permission"
            Manifest.permission.READ_CONTACTS -> "Open Contacts Permission"
            Manifest.permission.CALL_PHONE -> "Open Phone Permission"
            Manifest.permission.SEND_SMS -> "Open SMS Permission"
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION -> "Open Location Permission"
            else -> "Open App Permissions"
        }
        return AssistantUiAction(
            type = AssistantUiActionType.OPEN_SETTINGS,
            label = label,
            intentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            dataUri = Uri.fromParts("package", context.packageName, null).toString()
        )
    }

    fun writeSettings(context: Context): AssistantUiAction =
        AssistantUiAction(
            type = AssistantUiActionType.OPEN_SETTINGS,
            label = "Open Brightness Permission",
            intentAction = Settings.ACTION_MANAGE_WRITE_SETTINGS,
            dataUri = Uri.fromParts("package", context.packageName, null).toString()
        )

    fun notificationAccess(): AssistantUiAction =
        AssistantUiAction(
            type = AssistantUiActionType.OPEN_SETTINGS,
            label = "Open Notification Access",
            intentAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        )

    fun locationPreferences(): AssistantUiAction =
        AssistantUiAction(
            type = AssistantUiActionType.OPEN_SETTINGS,
            label = "Enable Location Preferences",
            intentAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS
        )
}
