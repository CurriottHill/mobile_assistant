package com.example.mobile_assistant

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationToolServiceTest {
    @Test
    fun execute_returnsCoordinatesWhenLocationIsAvailable() = runBlocking {
        val service = CurrentLocationToolService(
            context = Application(),
            currentLocationProvider = CurrentLocationProvider {
                CurrentLocationResolution.Ready(
                    latitude = 51.507400,
                    longitude = -0.127800
                )
            }
        )

        val result = service.execute(JSONObject())

        assertTrue(result.content.getBoolean("ok"))
        assertEquals(51.5074, result.content.getDouble("latitude"), 0.0)
        assertEquals(-0.1278, result.content.getDouble("longitude"), 0.0)
        assertTrue(result.chatResponse.contains("51.507400"))
    }

    @Test
    fun execute_whenLocationServicesDisabled_returnsSettingsButton() = runBlocking {
        val service = CurrentLocationToolService(
            context = Application(),
            currentLocationProvider = CurrentLocationProvider {
                CurrentLocationResolution.Failure(
                    error = "Location services are turned off.",
                    chatResponse = "Turn on location preferences so I can use your current location.",
                    uiAction = AssistantUiAction(
                        type = AssistantUiActionType.OPEN_SETTINGS,
                        label = "Enable Location Preferences"
                    ),
                    needsLocationEnabled = true
                )
            }
        )

        val result = service.execute(JSONObject())

        assertFalse(result.content.getBoolean("ok"))
        assertTrue(result.content.getBoolean("needs_location_enabled"))
        assertEquals("Location services are turned off.", result.content.getString("error"))
        assertNotNull(result.uiAction)
        assertEquals("Enable Location Preferences", result.uiAction?.label)
    }
}
