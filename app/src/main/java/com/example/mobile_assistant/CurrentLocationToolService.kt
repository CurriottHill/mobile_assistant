package com.example.mobile_assistant

import android.content.Context
import org.json.JSONObject
import java.util.Locale

internal fun interface CurrentLocationProvider {
    suspend fun resolve(context: Context): CurrentLocationResolution
}

internal sealed interface CurrentLocationResolution {
    data class Ready(
        val latitude: Double,
        val longitude: Double,
        val label: String = "your current location"
    ) : CurrentLocationResolution

    data class Failure(
        val error: String,
        val chatResponse: String,
        val uiAction: AssistantUiAction? = null,
        val needsPermission: String? = null,
        val needsLocationEnabled: Boolean = false
    ) : CurrentLocationResolution
}

internal class CurrentLocationToolService(
    private val context: Context,
    private val currentLocationProvider: CurrentLocationProvider = DeviceCurrentLocationProvider
) {

    suspend fun execute(arguments: JSONObject = JSONObject()): SharedToolExecutionResult {
        val resolution = currentLocationProvider.resolve(context)
        return when (resolution) {
            is CurrentLocationResolution.Ready -> SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_GET_LOCATION,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_GET_LOCATION)
                    .put("label", resolution.label)
                    .put("latitude", resolution.latitude)
                    .put("longitude", resolution.longitude)
                    .put(
                        "coordinates",
                        String.format(Locale.US, "%.6f,%.6f", resolution.latitude, resolution.longitude)
                    ),
                chatResponse = String.format(
                    Locale.US,
                    "Your current location is %.6f latitude and %.6f longitude.",
                    resolution.latitude,
                    resolution.longitude
                )
            )

            is CurrentLocationResolution.Failure -> SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_GET_LOCATION,
                content = JSONObject()
                    .put("ok", false)
                    .put("tool", SharedToolSchemas.TOOL_GET_LOCATION)
                    .put("label", "your current location")
                    .put("error", resolution.error)
                    .also { content ->
                        resolution.needsPermission?.let { content.put("needs_permission", it) }
                        if (resolution.needsLocationEnabled) {
                            content.put("needs_location_enabled", true)
                        }
                    },
                chatResponse = resolution.chatResponse,
                uiAction = resolution.uiAction ?: PermissionUiActions.locationPreferences()
            )
        }
    }
}

internal object DeviceCurrentLocationProvider : CurrentLocationProvider {
    override suspend fun resolve(context: Context): CurrentLocationResolution {
        return when (val resolution = DeviceTravelTimeCurrentLocationProvider.resolve(context)) {
            is TravelTimeOriginResolution.Failure -> CurrentLocationResolution.Failure(
                error = resolution.error,
                chatResponse = resolution.chatResponse,
                uiAction = resolution.uiAction,
                needsPermission = resolution.needsPermission,
                needsLocationEnabled = resolution.needsLocationEnabled
            )

            is TravelTimeOriginResolution.Ready -> {
                val coordinates = parseCoordinates(resolution.queryValue)
                if (coordinates == null) {
                    CurrentLocationResolution.Failure(
                        error = "Current location is unavailable.",
                        chatResponse = "I could not get your current location yet. Check location preferences and try again.",
                        uiAction = PermissionUiActions.locationPreferences()
                    )
                } else {
                    CurrentLocationResolution.Ready(
                        latitude = coordinates.first,
                        longitude = coordinates.second,
                        label = resolution.label
                    )
                }
            }
        }
    }

    private fun parseCoordinates(raw: String): Pair<Double, Double>? {
        val parts = raw.split(',').map { it.trim() }
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        return latitude to longitude
    }
}
