package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsUrlBuilderTest {

    @Test
    fun singleDestinationNoStopsUsesNavigationScheme() {
        val built = MapsNavigationUrls.build(
            destination = "London",
            waypoints = emptyList(),
            travelMode = "driving",
            avoid = null
        )
        assertTrue(built.usesNavigationScheme)
        assertEquals("google.navigation:q=London&mode=d", built.url)
    }

    @Test
    fun multiWordDestinationIsPercentEncoded() {
        val built = MapsNavigationUrls.build(
            destination = "10 Downing Street",
            waypoints = emptyList(),
            travelMode = "walking",
            avoid = null
        )
        assertEquals("google.navigation:q=10%20Downing%20Street&mode=w", built.url)
    }

    @Test
    fun homeToOxfordToLondonOmitsOriginAndOrdersWaypoints() {
        val built = MapsNavigationUrls.build(
            destination = "London",
            waypoints = listOf("Oxford"),
            travelMode = "driving",
            avoid = null
        )
        assertFalse(built.usesNavigationScheme)
        assertFalse("origin must be omitted", built.url.contains("origin="))
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=London" +
                "&waypoints=Oxford&travelmode=driving&dir_action=navigate",
            built.url
        )
    }

    @Test
    fun multipleWaypointsJoinedByLiteralPipe() {
        val built = MapsNavigationUrls.build(
            destination = "Paris",
            waypoints = listOf("Oxford", "London"),
            travelMode = "driving",
            avoid = null
        )
        assertTrue(built.url.contains("&waypoints=Oxford|London&"))
    }

    @Test
    fun transitForcesUniversalUrlEvenWithoutStops() {
        val built = MapsNavigationUrls.build(
            destination = "Berlin",
            waypoints = emptyList(),
            travelMode = "transit",
            avoid = null
        )
        assertFalse(built.usesNavigationScheme)
        assertTrue(built.url.contains("travelmode=transit"))
    }

    @Test
    fun avoidOnlyAppearsOnUniversalUrl() {
        val built = MapsNavigationUrls.build(
            destination = "Bath",
            waypoints = emptyList(),
            travelMode = "driving",
            avoid = "tolls"
        )
        assertFalse(built.usesNavigationScheme)
        assertTrue(built.url.contains("&avoid=tolls"))
    }

    @Test
    fun unknownTravelModeAndAvoidAreNormalized() {
        assertEquals("driving", MapsNavigationUrls.normalizeTravelMode("teleport"))
        assertEquals("walking", MapsNavigationUrls.normalizeTravelMode(" Walking "))
        assertEquals(null, MapsNavigationUrls.normalizeAvoid("dragons"))
        assertEquals("ferries", MapsNavigationUrls.normalizeAvoid("FERRIES"))
    }
}
