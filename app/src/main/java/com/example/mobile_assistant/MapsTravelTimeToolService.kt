package com.example.mobile_assistant

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal fun interface TravelTimeCurrentLocationProvider {
    suspend fun resolve(context: Context): TravelTimeOriginResolution
}

internal sealed interface TravelTimeOriginResolution {
    data class Ready(
        val queryValue: String,
        val label: String,
        val usesCurrentLocation: Boolean = false,
        val approximateQueryValue: String = queryValue
    ) : TravelTimeOriginResolution

    data class Failure(
        val error: String,
        val chatResponse: String,
        val uiAction: AssistantUiAction? = null,
        val needsPermission: String? = null,
        val needsLocationEnabled: Boolean = false
    ) : TravelTimeOriginResolution
}

/**
 * Headless travel time + distance (in miles). Uses the Google Distance Matrix API when a
 * MAPS_API_KEY is configured, otherwise falls back to a web-search estimate. Never opens Maps.
 */
internal class MapsTravelTimeToolService(
    private val context: Context,
    private val searchWeb: suspend (String) -> SearchWebResult,
    private val currentLocationProvider: TravelTimeCurrentLocationProvider = DeviceTravelTimeCurrentLocationProvider,
    private val mapsApiKey: String = runCatching { BuildConfig.MAPS_API_KEY }.getOrDefault("").trim(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun execute(arguments: JSONObject): SharedToolExecutionResult {
        val destination = arguments.optString("destination").trim()
        if (destination.isBlank()) {
            return errorResult("Missing destination.", "I need a destination to estimate travel time.")
        }
        val mode = normalizeMode(arguments.optString("travel_mode"))
        val allowApproximate = arguments.optBoolean("allow_approximate", false)

        val originResolution = resolveOrigin(arguments.optString("origin"))
        if (originResolution is TravelTimeOriginResolution.Failure) {
            return currentLocationFailureResult(originResolution)
        }
        originResolution as TravelTimeOriginResolution.Ready

        var apiDiagnostic: String? = null
        if (mapsApiKey.isNotBlank()) {
            val attempt = withContext(Dispatchers.IO) {
                queryDistanceMatrix(
                    apiKey = mapsApiKey,
                    originQuery = originResolution.queryValue,
                    originLabel = originResolution.label,
                    destination = destination,
                    mode = mode
                )
            }
            attempt.result?.let { return it }
            apiDiagnostic = attempt.diagnostic
        }

        if (originResolution.usesCurrentLocation && !allowApproximate) {
            val content = JSONObject()
                .put("ok", false)
                .put("tool", SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME)
                .put("origin", originResolution.label)
                .put("destination", destination)
                .put("mode", mode)
                .put("error", "Precise traffic aware travel time is unavailable right now.")
                .put("approximate_available", true)
            apiDiagnostic?.let { diagnostic ->
                content.put("api_error", diagnostic)
                AgentToolExecutorSupport.appendObservationError(content, diagnostic)
            }
            return SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME,
                content = content,
                chatResponse = "I could not get a precise traffic aware travel time from your current location just now. Would you like an approximate driving time?"
            )
        }

        val webResult = searchWeb(
            "$mode time and distance in miles from ${originResolution.approximateQueryValue} to $destination"
        )
        val answer = webResult.answer?.trim().orEmpty()
        val content = JSONObject()
            .put("ok", webResult.ok && answer.isNotBlank())
            .put("tool", SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME)
            .put("source", "web_estimate")
            .put("origin", originResolution.label)
            .put("destination", destination)
            .put("mode", mode)
            .put("approximate", true)
            .put("approximate_requested", allowApproximate)
        if (originResolution.queryValue != originResolution.label) {
            content.put("origin_query", originResolution.queryValue)
        }
        if (originResolution.approximateQueryValue != originResolution.queryValue) {
            content.put("approximate_origin_query", originResolution.approximateQueryValue)
        }
        if (answer.isNotBlank()) content.put("answer", answer)
        webResult.error?.let { content.put("error", it) }
        apiDiagnostic?.let { content.put("api_error", it) }

        val chatResponse = if (answer.isNotBlank()) {
            "Approximately, $answer"
        } else {
            "I could not estimate the travel time to $destination just now."
        }
        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME,
            content = content,
            chatResponse = chatResponse
        )
    }

    private suspend fun resolveOrigin(rawOrigin: String?): TravelTimeOriginResolution {
        val originArg = rawOrigin?.trim().orEmpty()
        if (originArg.isBlank()) {
            return currentLocationProvider.resolve(context)
        }

        val originResolution = NamedPlaceResolver.resolve(context, originArg)
        if (originResolution.skipAsCurrentLocation) {
            return currentLocationProvider.resolve(context)
        }

        val origin = originResolution.value.ifBlank { originArg }
        return TravelTimeOriginResolution.Ready(
            queryValue = origin,
            label = origin,
            usesCurrentLocation = false,
            approximateQueryValue = origin
        )
    }

    private data class DistanceMatrixAttempt(
        val result: SharedToolExecutionResult?,
        val diagnostic: String?
    ) {
        companion object {
            fun success(result: SharedToolExecutionResult) = DistanceMatrixAttempt(result, null)
            fun failed(diagnostic: String) = DistanceMatrixAttempt(null, diagnostic)
        }
    }

    private fun queryDistanceMatrix(
        apiKey: String,
        originQuery: String,
        originLabel: String,
        destination: String,
        mode: String
    ): DistanceMatrixAttempt {
        val url = buildString {
            append("https://maps.googleapis.com/maps/api/distancematrix/json")
            append("?origins=").append(Uri.encode(originQuery))
            append("&destinations=").append(Uri.encode(destination))
            append("&mode=").append(mode)
            append("&units=imperial")
            append("&departure_time=now")
            append("&key=").append(apiKey)
        }
        return runCatching {
            httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) {
                    return logAndFail(
                        "HTTP ${resp.code} from Distance Matrix" +
                            body.take(300).let { if (it.isBlank()) "" else ": $it" }
                    )
                }
                val json = JSONObject(body)
                val status = json.optString("status")
                if (status != "OK") {
                    val errorMessage = json.optString("error_message").trim()
                    return logAndFail(
                        "Distance Matrix status=$status" +
                            if (errorMessage.isNotBlank()) ": $errorMessage" else ""
                    )
                }
                val element = json.optJSONArray("rows")?.optJSONObject(0)
                    ?.optJSONArray("elements")?.optJSONObject(0)
                    ?: return logAndFail("Distance Matrix returned no route elements")
                val elementStatus = element.optString("status")
                if (elementStatus != "OK") {
                    return logAndFail("Distance Matrix element status=$elementStatus")
                }

                val distanceText = element.optJSONObject("distance")?.optString("text").orEmpty()
                val distanceMeters = element.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
                val distanceMiles = distanceMeters / 1609.344
                val durationText = element.optJSONObject("duration")?.optString("text").orEmpty()
                val trafficText = element.optJSONObject("duration_in_traffic")?.optString("text")

                val content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME)
                    .put("source", "distance_matrix")
                    .put("origin", originLabel)
                    .put("destination", destination)
                    .put("mode", mode)
                    .put("distance_text", distanceText)
                    .put("distance_miles", String.format(Locale.US, "%.1f", distanceMiles).toDouble())
                    .put("duration_text", durationText)
                if (originQuery != originLabel) {
                    content.put("origin_query", originQuery)
                }
                if (!trafficText.isNullOrBlank()) content.put("duration_in_traffic_text", trafficText)

                val spokenDuration = trafficText?.takeIf { it.isNotBlank() } ?: durationText
                DistanceMatrixAttempt.success(
                    SharedToolExecutionResult(
                        toolName = SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME,
                        content = content,
                        chatResponse = "About $spokenDuration, ${String.format(Locale.US, "%.0f", distanceMiles)} miles, $mode."
                    )
                )
            }
        }.getOrElse { throwable ->
            logAndFail("Distance Matrix request error: ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun logAndFail(diagnostic: String): DistanceMatrixAttempt {
        Log.w("MapsTravelTime", diagnostic)
        return DistanceMatrixAttempt.failed(diagnostic)
    }

    private fun normalizeMode(raw: String?): String {
        val v = raw?.trim()?.lowercase().orEmpty()
        return if (v in setOf("driving", "walking", "bicycling", "transit")) v else "driving"
    }

    private fun currentLocationFailureResult(
        failure: TravelTimeOriginResolution.Failure
    ) = SharedToolExecutionResult(
        toolName = SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME,
        content = JSONObject()
            .put("ok", false)
            .put("tool", SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME)
            .put("origin", "your current location")
            .put("error", failure.error)
            .also { content ->
                failure.needsPermission?.let { content.put("needs_permission", it) }
                if (failure.needsLocationEnabled) {
                    content.put("needs_location_enabled", true)
                }
            },
        chatResponse = failure.chatResponse,
        uiAction = failure.uiAction ?: PermissionUiActions.locationPreferences()
    )

    private fun errorResult(error: String, chatResponse: String) = SharedToolExecutionResult(
        toolName = SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME,
        content = JSONObject()
            .put("ok", false)
            .put("tool", SharedToolSchemas.TOOL_MAPS_TRAVEL_TIME)
            .put("error", error),
        chatResponse = chatResponse
    )
}

internal object DeviceTravelTimeCurrentLocationProvider : TravelTimeCurrentLocationProvider {
    override suspend fun resolve(context: Context): TravelTimeOriginResolution {
        if (!SetupChecks.hasLocationPermission(context)) {
            return TravelTimeOriginResolution.Failure(
                error = "Location permission is not granted.",
                chatResponse = "Enable location preferences so I can use your current location for travel time.",
                uiAction = PermissionUiActions.appPermission(context, Manifest.permission.ACCESS_FINE_LOCATION),
                needsPermission = "LOCATION"
            )
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return TravelTimeOriginResolution.Failure(
                error = "Location services are unavailable on this device.",
                chatResponse = "I could not access location services on this device."
            )

        if (!isLocationEnabled(locationManager)) {
            return TravelTimeOriginResolution.Failure(
                error = "Location services are turned off.",
                chatResponse = "Turn on location preferences so I can use your current location for travel time.",
                uiAction = PermissionUiActions.locationPreferences(),
                needsLocationEnabled = true
            )
        }

        val location = requestBestLocation(context, locationManager)
            ?: return TravelTimeOriginResolution.Failure(
                error = "Current location is unavailable.",
                chatResponse = "I could not get your current location yet. Check location preferences and try again.",
                uiAction = PermissionUiActions.locationPreferences()
            )
        val coordinateQuery = String.format(Locale.US, "%.6f,%.6f", location.latitude, location.longitude)
        val approximateQuery = reverseGeocodeApproximateQuery(context, location) ?: coordinateQuery

        return TravelTimeOriginResolution.Ready(
            queryValue = coordinateQuery,
            label = "your current location",
            usesCurrentLocation = true,
            approximateQueryValue = approximateQuery
        )
    }

    private suspend fun requestBestLocation(
        context: Context,
        locationManager: LocationManager
    ): Location? {
        val current = requestCurrentLocation(context, locationManager)
        if (current != null) return current
        return candidateProviders(locationManager)
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter(TravelTimeLocationQuality::isAcceptableLastKnown)
            .maxByOrNull(Location::getTime)
    }

    private suspend fun requestCurrentLocation(
        context: Context,
        locationManager: LocationManager
    ): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = candidateProviders(locationManager).firstOrNull() ?: return null
        return withTimeoutOrNull(6_000L) {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    context.mainExecutor
                ) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            }
        }
    }

    private fun candidateProviders(locationManager: LocationManager): List<String> {
        val preferred = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val enabled = preferred.filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        val extra = runCatching { locationManager.getProviders(true) }.getOrDefault(emptyList())
        return (enabled + extra).distinct()
    }

    private fun isLocationEnabled(locationManager: LocationManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            candidateProviders(locationManager).isNotEmpty()
        }
    }

    private fun reverseGeocodeApproximateQuery(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
        }.getOrNull()?.let { address ->
            listOfNotNull(
                address.locality?.trim().takeUnless { it.isNullOrBlank() },
                address.adminArea?.trim().takeUnless { it.isNullOrBlank() }
            ).takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
    }
}

internal object TravelTimeLocationQuality {
    private const val MAX_LAST_KNOWN_AGE_MILLIS = 10 * 60 * 1000L
    private const val MAX_LAST_KNOWN_ACCURACY_METERS = 2_000f

    fun isAcceptableLastKnown(location: Location): Boolean {
        val timestamp = location.time
        if (timestamp <= 0L) return false
        val ageMillis = System.currentTimeMillis() - timestamp
        return isAcceptableLastKnown(
            ageMillis = ageMillis,
            hasAccuracy = location.hasAccuracy(),
            accuracyMeters = location.accuracy
        )
    }

    fun isAcceptableLastKnown(
        ageMillis: Long,
        hasAccuracy: Boolean,
        accuracyMeters: Float
    ): Boolean {
        if (ageMillis < 0L || ageMillis > MAX_LAST_KNOWN_AGE_MILLIS) return false
        if (hasAccuracy && accuracyMeters > MAX_LAST_KNOWN_ACCURACY_METERS) return false
        return true
    }
}
