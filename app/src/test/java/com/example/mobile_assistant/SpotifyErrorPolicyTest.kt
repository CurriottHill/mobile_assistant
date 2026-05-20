package com.example.mobile_assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyErrorPolicyTest {

    @Test
    fun reconnectsOnUnauthorized() {
        assertTrue(
            SpotifyErrorPolicy.needsReconnect(
                statusCode = 401,
                errorMessage = "Spotify login expired."
            )
        )
    }

    @Test
    fun reconnectsOnScopeForbidden() {
        assertTrue(
            SpotifyErrorPolicy.needsReconnect(
                statusCode = 403,
                errorMessage = "Insufficient client scope"
            )
        )
    }

    @Test
    fun reconnectsOnPermissionForbidden() {
        assertTrue(
            SpotifyErrorPolicy.needsReconnect(
                statusCode = 403,
                errorMessage = "Missing playlist permissions"
            )
        )
    }

    @Test
    fun doesNotReconnectOnGenericForbidden() {
        assertFalse(
            SpotifyErrorPolicy.needsReconnect(
                statusCode = 403,
                errorMessage = "You cannot create a playlist for another user"
            )
        )
    }

    @Test
    fun doesNotReconnectOnRateLimit() {
        assertFalse(
            SpotifyErrorPolicy.needsReconnect(
                statusCode = 429,
                errorMessage = "Rate limit exceeded"
            )
        )
    }
}
