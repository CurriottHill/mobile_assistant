package com.example.mobile_assistant

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal class WeatherToolService(
    private val context: Context,
    private val currentLocationProvider: CurrentLocationProvider = DeviceCurrentLocationProvider,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    suspend fun execute(arguments: JSONObject): SharedToolExecutionResult {
        val locationArg = arguments.optString("location").trim()
        val days = arguments.optInt("days", 1).coerceIn(0, 7)

        if (locationArg.isBlank()) {
            return errorResult("I need a location to check the weather.")
        }

        return withContext(Dispatchers.IO) {
            val coords = resolveCoordinates(locationArg)
            if (coords.error != null) return@withContext errorResult(coords.error)

            val lat = coords.latitude!!
            val lon = coords.longitude!!
            val locationName = coords.name!!
            val isMetric = coords.countryCode?.uppercase() != "US"
            val forecastDays = (days + 1).coerceIn(1, 8)

            val tempUnit = if (isMetric) "celsius" else "fahrenheit"
            val windUnit = if (isMetric) "kmh" else "mph"
            val precipUnit = if (isMetric) "mm" else "inch"

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
                "&temperature_unit=$tempUnit&wind_speed_unit=$windUnit&precipitation_unit=$precipUnit" +
                "&timezone=auto&forecast_days=$forecastDays"

            val body = fetchUrl(url) ?: return@withContext errorResult("Could not reach the weather service.")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext errorResult("Could not parse weather data.")

            buildResult(json, locationName, days, isMetric)
        }
    }

    private suspend fun resolveCoordinates(locationArg: String): CoordResult {
        val lower = locationArg.lowercase()
        val isCurrentLocation = lower == "here" || lower == "current" ||
            lower.contains("current location") || lower.contains("my location")

        if (isCurrentLocation) {
            return when (val res = currentLocationProvider.resolve(context)) {
                is CurrentLocationResolution.Ready -> {
                    val countryCode = countryCodeFromGps(res.latitude, res.longitude)
                    CoordResult(
                        latitude = res.latitude,
                        longitude = res.longitude,
                        name = res.label.ifBlank { "your location" },
                        countryCode = countryCode
                    )
                }
                is CurrentLocationResolution.Failure -> CoordResult(error = res.chatResponse)
            }
        }

        val encoded = URLEncoder.encode(locationArg, "UTF-8")
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
        val body = fetchUrl(geoUrl) ?: return CoordResult(error = "Could not reach the geocoding service.")
        val geoJson = runCatching { JSONObject(body) }.getOrNull()
            ?: return CoordResult(error = "Could not parse location data.")

        val results = geoJson.optJSONArray("results")
        if (results == null || results.length() == 0) {
            return CoordResult(error = "I couldn't find a location called \"$locationArg\".")
        }

        val place = results.getJSONObject(0)
        val countryCode = place.optString("country_code").ifBlank { null }
        val name = buildString {
            append(place.optString("name"))
            val admin = place.optString("admin1").ifBlank { null }
            if (admin != null) { append(", "); append(admin) }
            if (countryCode != null && countryCode != "US") { append(", "); append(countryCode) }
        }
        return CoordResult(
            latitude = place.getDouble("latitude"),
            longitude = place.getDouble("longitude"),
            name = name,
            countryCode = countryCode
        )
    }

    private fun countryCodeFromGps(lat: Double, lon: Double): String? {
        return runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.US).getFromLocation(lat, lon, 1)
                ?.firstOrNull()?.countryCode
        }.getOrNull()
    }

    private fun buildResult(
        json: JSONObject,
        locationName: String,
        requestedDays: Int,
        isMetric: Boolean
    ): SharedToolExecutionResult {
        val current = json.optJSONObject("current")
        val daily = json.optJSONObject("daily")
        val tempUnit = if (isMetric) "C" else "F"
        val windUnit = if (isMetric) "km/h" else "mph"
        val precipUnit = if (isMetric) "mm" else "in"

        val content = JSONObject()
            .put("ok", true)
            .put("tool", SharedToolSchemas.TOOL_GET_WEATHER)
            .put("location", locationName)
            .put("temperature_unit", tempUnit)
            .put("wind_unit", windUnit)
            .put("precip_unit", precipUnit)

        val weatherCode = current?.optInt("weather_code") ?: 0
        val condition = wmoCondition(weatherCode)
        val currentTemp = current?.optDouble("temperature_2m")

        if (current != null) {
            content.put("current", JSONObject()
                .put("temperature", currentTemp?.roundToInt() ?: JSONObject.NULL)
                .put("feels_like", current.optDouble("apparent_temperature").roundToInt())
                .put("precipitation", current.optDouble("precipitation"))
                .put("wind_speed", current.optDouble("wind_speed_10m"))
                .put("condition", condition)
            )
        }

        if (daily != null && requestedDays > 0) {
            val dates = daily.optJSONArray("time")
            val maxTemps = daily.optJSONArray("temperature_2m_max")
            val minTemps = daily.optJSONArray("temperature_2m_min")
            val precipSums = daily.optJSONArray("precipitation_sum")
            val weatherCodes = daily.optJSONArray("weather_code")
            val dayNameFmt = SimpleDateFormat("EEEE", Locale.US)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            val dailyArray = org.json.JSONArray()
            val limit = minOf(dates?.length() ?: 0, requestedDays + 1)
            for (i in 0 until limit) {
                val date = dates?.optString(i) ?: continue
                val dayLabel = if (i == 0) "Today" else runCatching {
                    dayNameFmt.format(dateFmt.parse(date)!!)
                }.getOrElse { date }

                dailyArray.put(JSONObject()
                    .put("date", date)
                    .put("day", dayLabel)
                    .put("high", maxTemps?.optDouble(i)?.roundToInt() ?: JSONObject.NULL)
                    .put("low", minTemps?.optDouble(i)?.roundToInt() ?: JSONObject.NULL)
                    .put("precipitation", precipSums?.optDouble(i) ?: JSONObject.NULL)
                    .put("condition", weatherCodes?.optInt(i)?.let { wmoCondition(it) } ?: "")
                )
            }
            content.put("daily", dailyArray)
        }

        val chatResponse = if (currentTemp != null) {
            "$condition, ${currentTemp.roundToInt()}°$tempUnit in $locationName."
        } else {
            "Weather data retrieved for $locationName."
        }

        return SharedToolExecutionResult(
            toolName = SharedToolSchemas.TOOL_GET_WEATHER,
            content = content,
            chatResponse = chatResponse
        )
    }

    private fun fetchUrl(url: String): String? {
        return runCatching {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { it.body?.string() }
        }.getOrNull()
    }

    private fun wmoCondition(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Rain showers"
        82 -> "Heavy rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    private fun errorResult(message: String) = SharedToolExecutionResult(
        toolName = SharedToolSchemas.TOOL_GET_WEATHER,
        content = JSONObject()
            .put("ok", false)
            .put("tool", SharedToolSchemas.TOOL_GET_WEATHER)
            .put("error", message),
        chatResponse = message
    )

    private data class CoordResult(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val name: String? = null,
        val countryCode: String? = null,
        val error: String? = null
    )
}
