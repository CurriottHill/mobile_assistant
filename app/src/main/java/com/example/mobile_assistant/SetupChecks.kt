package com.example.mobile_assistant

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

/**
 * Stateless device-setup detection + intent helpers shared by the onboarding wizard,
 * the dashboard, and the per-setting screens. Single source of truth — no duplicated
 * Settings.Secure parsing or intent construction.
 */
object SetupChecks {

    private val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    val REQUIRED_RUNTIME_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    private val PERMISSION_LABELS = linkedMapOf(
        Manifest.permission.RECORD_AUDIO to "microphone",
        Manifest.permission.READ_CONTACTS to "contacts",
        Manifest.permission.CALL_PHONE to "phone",
        Manifest.permission.SEND_SMS to "SMS",
        Manifest.permission.READ_CALENDAR to "calendar",
        Manifest.permission.WRITE_CALENDAR to "calendar editing"
    )

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val expectedService =
            ComponentName(context, AssistantAccessibilityService::class.java).flattenToString()

        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1 && enabledServices.split(':').any { it.equals(expectedService, ignoreCase = true) }
    }

    fun openAccessibilitySettings(activity: Activity) {
        val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val componentName =
            ComponentName(activity.packageName, AssistantAccessibilityService::class.java.name)
        accessibilityIntent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
        accessibilityIntent.putExtra("page_fragment_args_key", componentName.flattenToString())
        activity.startActivity(accessibilityIntent)
    }

    fun isDefaultAssistantApp(context: Context): Boolean {
        val assistantSetting =
            Settings.Secure.getString(context.contentResolver, "assistant").orEmpty()
        if (assistantSetting.isBlank()) return false

        val mainActivity =
            ComponentName(context, AssistantActivity::class.java).flattenToString()
        val packagePrefix = "${context.packageName}/"
        return assistantSetting.startsWith(mainActivity) ||
            assistantSetting.startsWith(packagePrefix)
    }

    fun openDefaultAssistantSettings(activity: Activity) {
        Toast.makeText(
            activity,
            activity.getString(R.string.default_assistant_settings_instruction),
            Toast.LENGTH_LONG
        ).show()

        val opened = assistantSettingsIntents().any { candidate ->
            tryStartActivity(activity, candidate)
        }

        if (!opened) {
            Toast.makeText(
                activity,
                activity.getString(R.string.default_assistant_settings_unavailable),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun assistantSettingsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )

    private fun tryStartActivity(activity: Activity, intent: Intent): Boolean {
        if (intent.resolveActivity(activity.packageManager) == null) return false
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun missingRuntimePermissionLabels(context: Context): List<String> =
        PERMISSION_LABELS.mapNotNull { (permission, label) ->
            if (ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                null
            } else {
                label
            }
        }

    fun hasLocationPermission(context: Context): Boolean {
        return LOCATION_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            runCatching { locationManager.getProviders(true).isNotEmpty() }.getOrDefault(false)
        }
    }

    fun isLocationReady(context: Context): Boolean {
        return hasLocationPermission(context) && isLocationEnabled(context)
    }

    fun locationStatusLabel(context: Context): String {
        val hasPermission = hasLocationPermission(context)
        val enabled = isLocationEnabled(context)
        return when {
            hasPermission && enabled -> context.getString(R.string.location_status_ready)
            !hasPermission && !enabled -> context.getString(R.string.location_status_missing_both)
            !hasPermission -> context.getString(R.string.location_status_missing_permission)
            else -> context.getString(R.string.location_status_missing_preferences)
        }
    }

    fun requestMissingPermissions(
        context: Context,
        launcher: ActivityResultLauncher<Array<String>>
    ) {
        val missing = REQUIRED_RUNTIME_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }.toMutableList()
        if (missing.isEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.permissions_status_ready),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        launcher.launch(missing.toTypedArray())
    }

    fun requestLocationPermissions(
        context: Context,
        launcher: ActivityResultLauncher<Array<String>>
    ) {
        val missing = LOCATION_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.location_permission_ready),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        launcher.launch(missing.toTypedArray())
    }

    fun openAppDetailsSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)
        )
        if (!tryStartActivity(activity, intent)) {
            tryStartActivity(activity, Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun openLocationSettings(activity: Activity) {
        if (!tryStartActivity(activity, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))) {
            tryStartActivity(activity, Intent(Settings.ACTION_SETTINGS))
        }
    }

    internal fun launchSpotifyLogin(activity: Activity, spotifyService: SpotifyService) {
        val launchResult = spotifyService.createLoginIntent()
        if (!launchResult.ok || launchResult.intent == null) {
            Toast.makeText(activity, launchResult.message, Toast.LENGTH_LONG).show()
            return
        }
        if (launchResult.intent.resolveActivity(activity.packageManager) == null) {
            Toast.makeText(
                activity,
                activity.getString(R.string.spotify_no_browser_found),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        activity.startActivity(launchResult.intent)
    }

    internal fun launchGoogleLogin(activity: Activity, googleAccountService: GoogleAccountService) {
        val launchResult = googleAccountService.createLoginIntent()
        if (!launchResult.ok || launchResult.intent == null) {
            Toast.makeText(activity, launchResult.message, Toast.LENGTH_LONG).show()
            return
        }
        activity.startActivity(launchResult.intent)
    }
}
