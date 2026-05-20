package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyUriSupportTest {

    @Test
    fun keepsProvidedUriWhenPresent() {
        assertEquals(
            "spotify:playlist:abc123",
            SpotifyUriSupport.canonicalUri(
                rawUri = "spotify:playlist:abc123",
                itemType = "playlist",
                fallbackId = "ignored"
            )
        )
    }

    @Test
    fun buildsUriFromIdWhenUriIsMissing() {
        assertEquals(
            "spotify:playlist:abc123",
            SpotifyUriSupport.canonicalUri(
                rawUri = "   ",
                itemType = "playlist",
                fallbackId = "abc123"
            )
        )
    }

    @Test
    fun returnsNullWhenBothUriAndIdAreMissing() {
        assertNull(
            SpotifyUriSupport.canonicalUri(
                rawUri = "",
                itemType = "playlist",
                fallbackId = " "
            )
        )
    }
}
