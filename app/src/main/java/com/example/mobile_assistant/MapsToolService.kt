package com.example.mobile_assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Starts Google Maps turn-by-turn navigation through structured intents/deep links so the
 * agent never has to drive the Maps UI. UI navigation remains the fallback when this fails.
 */
internal class MapsToolService(private val context: Context) {

    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    fun executeStartNavigation(arguments: JSONObject): SharedToolExecutionResult {
        val destination = arguments.optString("destination").trim()
        if (destination.isBlank()) {
            return errorResult(
                error = "Missing destination.",
                chatResponse = "I need a destination first."
            )
        }

        val rawWaypoints = parseWaypoints(arguments)
        val travelMode = MapsNavigationUrls.normalizeTravelMode(arguments.optString("travel_mode"))
        val avoid = MapsNavigationUrls.normalizeAvoid(arguments.optString("avoid"))

        val unresolvedNamedPlaces = mutableListOf<String>()
        val destinationResolution = NamedPlaceResolver.resolve(context, destination)
        if (!destinationResolution.resolved) unresolvedNamedPlaces.add(destination)
        val resolvedDestination = destinationResolution.value.ifBlank { destination }

        val waypoints = rawWaypoints.mapNotNull { wp ->
            val res = NamedPlaceResolver.resolve(context, wp)
            if (!res.resolved) unresolvedNamedPlaces.add(wp)
            // A "current location" waypoint makes no sense mid-route; drop it.
            if (res.skipAsCurrentLocation) null else res.value.ifBlank { wp }
        }

        val built = MapsNavigationUrls.build(
            destination = resolvedDestination,
            waypoints = waypoints,
            travelMode = travelMode,
            avoid = avoid
        )

        val baseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(built.url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Prefer the Google Maps app; fall back to whatever can handle the URL (browser /
        // Maps web), which is the second tier before the accessibility UI loop.
        val mapsIntent = Intent(baseIntent).setPackage(MAPS_PACKAGE)
        val usedPackage: String? = when {
            resolveActivity(mapsIntent) != null -> MAPS_PACKAGE
            else -> null
        }
        val launchIntent = if (usedPackage != null) mapsIntent else baseIntent

        return runCatching {
            context.startActivity(launchIntent)
            SharedToolExecutionResult(
                toolName = SharedToolSchemas.TOOL_START_NAVIGATION,
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", SharedToolSchemas.TOOL_START_NAVIGATION)
                    .put("destination", resolvedDestination)
                    .put("waypoints", JSONArray().also { arr -> waypoints.forEach(arr::put) })
                    .put("travel_mode", travelMode)
                    .also { c ->
                        avoid?.let { c.put("avoid", it) }
                        if (unresolvedNamedPlaces.isNotEmpty()) {
                            c.put("resolved", false)
                            c.put(
                                "unresolved_places",
                                JSONArray().also { a -> unresolvedNamedPlaces.forEach(a::put) }
                            )
                        }
                    }
                    .put("url", built.url)
                    .put("used_package", usedPackage ?: "default"),
                chatResponse = buildSpokenMessage(resolvedDestination, waypoints)
            )
        }.getOrElse { error ->
            errorResult(
                error = error.message ?: "Failed to start navigation.",
                chatResponse = "I could not start navigation just now."
            )
        }
    }

    private fun parseWaypoints(arguments: JSONObject): List<String> {
        val array = arguments.optJSONArray("waypoints")
        if (array != null) {
            return (0 until array.length())
                .mapNotNull { array.optString(it).trim().ifBlank { null } }
        }
        // Be lenient if the model passes a single waypoint as a plain string.
        return arguments.optString("waypoints").trim()
            .ifBlank { null }
            ?.let { listOf(it) }
            ?: emptyList()
    }

    private fun resolveActivity(intent: Intent): ResolveInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    private fun buildSpokenMessage(destination: String, waypoints: List<String>): String {
        return buildString {
            append("Starting navigation to ")
            append(destination)
            if (waypoints.isNotEmpty()) {
                append(if (waypoints.size == 1) " with a stop in " else " with stops in ")
                append(joinNatural(waypoints))
            }
            append(".")
        }
    }

    private fun joinNatural(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> parts.dropLast(1).joinToString(", ") + ", and ${parts.last()}"
    }

    private fun errorResult(error: String, chatResponse: String) = SharedToolExecutionResult(
        toolName = SharedToolSchemas.TOOL_START_NAVIGATION,
        content = JSONObject()
            .put("ok", false)
            .put("tool", SharedToolSchemas.TOOL_START_NAVIGATION)
            .put("error", error),
        chatResponse = chatResponse
    )
}

/**
 * Pure URL/URI builder for Maps navigation. No Android dependencies so it is unit-testable
 * the same way [AppOpener]'s matching logic is.
 */
internal object MapsNavigationUrls {

    private val TRAVEL_MODES = setOf("driving", "walking", "bicycling", "transit")
    private val AVOID_OPTIONS = setOf("tolls", "highways", "ferries")

    data class Built(val url: String, val usesNavigationScheme: Boolean)

    fun normalizeTravelMode(raw: String?): String {
        val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
        return if (value in TRAVEL_MODES) value else "driving"
    }

    fun normalizeAvoid(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
        return value.takeIf { it in AVOID_OPTIONS }
    }

    /**
     * Single destination with no stops/avoid and a mode the navigation scheme supports goes
     * straight to turn-by-turn via `google.navigation:`. Anything needing ordered waypoints,
     * an `avoid` filter, or transit uses the Maps Universal URL with `dir_action=navigate`.
     * Origin is always omitted so Maps starts from the user's current location.
     */
    fun build(
        destination: String,
        waypoints: List<String>,
        travelMode: String,
        avoid: String?
    ): Built {
        val dest = destination.trim()
        val stops = waypoints.map { it.trim() }.filter { it.isNotEmpty() }
        val mode = normalizeTravelMode(travelMode)
        val avoidOption = normalizeAvoid(avoid)

        val mustUseUniversalUrl = stops.isNotEmpty() || avoidOption != null || mode == "transit"
        if (!mustUseUniversalUrl) {
            val url = "google.navigation:q=${encodeComponent(dest)}&mode=${navigationModeCode(mode)}"
            return Built(url, usesNavigationScheme = true)
        }

        val url = buildString {
            append("https://www.google.com/maps/dir/?api=1")
            append("&destination=").append(encodeComponent(dest))
            if (stops.isNotEmpty()) {
                append("&waypoints=")
                append(stops.joinToString("|") { encodeComponent(it) })
            }
            append("&travelmode=").append(mode)
            if (avoidOption != null) {
                append("&avoid=").append(avoidOption)
            }
            append("&dir_action=navigate")
        }
        return Built(url, usesNavigationScheme = false)
    }

    private fun navigationModeCode(travelMode: String): String = when (travelMode) {
        "walking" -> "w"
        "bicycling" -> "b"
        else -> "d"
    }

    /** RFC-3986-ish component encoding using only the JVM so unit tests don't need Android. */
    internal fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
