package com.example.mobile_assistant

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsTravelTimeToolServiceTest {
    @Test
    fun execute_withoutOrigin_returnsLocationPermissionAction() = runBlocking {
        val service = MapsTravelTimeToolService(
            context = Application(),
            searchWeb = { error("searchWeb should not be called when location is unavailable") },
            currentLocationProvider = TravelTimeCurrentLocationProvider {
                TravelTimeOriginResolution.Failure(
                    error = "Location permission is not granted.",
                    chatResponse = "Enable location preferences so I can use your current location for travel time.",
                    uiAction = AssistantUiAction(
                        type = AssistantUiActionType.OPEN_SETTINGS,
                        label = "Open Location Permission"
                    ),
                    needsPermission = "LOCATION"
                )
            }
        )

        val result = service.execute(JSONObject().put("destination", "Heathrow Airport"))

        assertFalse(result.content.getBoolean("ok"))
        assertEquals("your current location", result.content.getString("origin"))
        assertEquals("LOCATION", result.content.getString("needs_permission"))
        assertEquals(
            "Enable location preferences so I can use your current location for travel time.",
            result.chatResponse
        )
        assertNotNull(result.uiAction)
        assertEquals("Open Location Permission", result.uiAction?.label)
    }

    @Test
    fun execute_whenCurrentLocationUnavailable_defaultsToLocationSettingsAction() = runBlocking {
        val service = MapsTravelTimeToolService(
            context = Application(),
            searchWeb = { error("searchWeb should not be called when location is unavailable") },
            currentLocationProvider = TravelTimeCurrentLocationProvider {
                TravelTimeOriginResolution.Failure(
                    error = "Current location is unavailable.",
                    chatResponse = "I could not get your current location yet."
                )
            }
        )

        val result = service.execute(JSONObject().put("destination", "Heathrow Airport"))

        assertFalse(result.content.getBoolean("ok"))
        assertEquals("Current location is unavailable.", result.content.getString("error"))
        assertNotNull(result.uiAction)
        assertEquals("Enable Location Preferences", result.uiAction?.label)
    }

    @Test
    fun execute_withCurrentLocation_doesNotFallBackToApproximateWebEstimate() = runBlocking {
        var searchCalled = false
        val service = MapsTravelTimeToolService(
            context = Application(),
            searchWeb = {
                searchCalled = true
                SearchWebResult(ok = true, answer = "about 20 minutes")
            },
            currentLocationProvider = TravelTimeCurrentLocationProvider {
                TravelTimeOriginResolution.Ready(
                    queryValue = "50.880000,-2.800000",
                    label = "your current location",
                    usesCurrentLocation = true
                )
            },
            mapsApiKey = ""
        )

        val result = service.execute(JSONObject().put("destination", "Crewkerne"))

        assertFalse(result.content.getBoolean("ok"))
        assertFalse(searchCalled)
        assertEquals(
            "Precise traffic aware travel time is unavailable right now.",
            result.content.getString("error")
        )
        assertTrue(result.content.getBoolean("approximate_available"))
        assertTrue(result.chatResponse.contains("approximate driving time"))
    }

    @Test
    fun execute_withCurrentLocationAndApproximateAllowed_usesApproximateFallback() = runBlocking {
        var searchQuery: String? = null
        val service = MapsTravelTimeToolService(
            context = Application(),
            searchWeb = { query ->
                searchQuery = query
                SearchWebResult(ok = true, answer = "about 25 minutes and 14 miles")
            },
            currentLocationProvider = TravelTimeCurrentLocationProvider {
                TravelTimeOriginResolution.Ready(
                    queryValue = "50.880000,-2.800000",
                    label = "your current location",
                    usesCurrentLocation = true,
                    approximateQueryValue = "Crewkerne, Somerset"
                )
            },
            mapsApiKey = ""
        )

        val result = service.execute(
            JSONObject()
                .put("destination", "Yeovil")
                .put("allow_approximate", true)
        )

        assertTrue(result.content.getBoolean("ok"))
        assertEquals("web_estimate", result.content.getString("source"))
        assertEquals("Crewkerne, Somerset", result.content.getString("approximate_origin_query"))
        assertEquals(
            "driving time and distance in miles from Crewkerne, Somerset to Yeovil",
            searchQuery
        )
    }

    @Test
    fun lastKnownLocationRejectsStaleFixes() {
        assertFalse(
            TravelTimeLocationQuality.isAcceptableLastKnown(
                ageMillis = 11 * 60 * 1000L,
                hasAccuracy = true,
                accuracyMeters = 25f
            )
        )
        assertTrue(
            TravelTimeLocationQuality.isAcceptableLastKnown(
                ageMillis = 2 * 60 * 1000L,
                hasAccuracy = true,
                accuracyMeters = 25f
            )
        )
    }
}
