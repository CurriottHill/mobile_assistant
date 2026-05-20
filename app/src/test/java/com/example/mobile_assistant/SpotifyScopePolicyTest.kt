package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyScopePolicyTest {

    @Test
    fun storesReturnedScopeWhenPresent() {
        val stored = SpotifyScopePolicy.scopesToStore(
            responseScope = "user-read-private playlist-modify-private",
            previousScope = "user-read-private",
            preservePrevious = false,
            requestedScope = "user-read-private playlist-modify-public"
        )

        assertEquals("user-read-private playlist-modify-private", stored)
    }

    @Test
    fun storesRequestedScopeWhenInitialTokenResponseOmitsScope() {
        val stored = SpotifyScopePolicy.scopesToStore(
            responseScope = "",
            previousScope = "",
            preservePrevious = false,
            requestedScope = "user-read-private playlist-modify-private playlist-modify-public"
        )

        assertEquals("user-read-private playlist-modify-private playlist-modify-public", stored)
    }

    @Test
    fun preservesPreviousScopeDuringRefresh() {
        val stored = SpotifyScopePolicy.scopesToStore(
            responseScope = "",
            previousScope = "user-read-private",
            preservePrevious = true,
            requestedScope = "user-read-private playlist-modify-private"
        )

        assertEquals("user-read-private", stored)
    }

    @Test
    fun publicModifyScopeDoesNotAllowDefaultPrivatePlaylists() {
        assertEquals(
            "playlist-modify-private",
            SpotifyScopePolicy.missingPlaylistModifyScope(
                scopes = "user-read-private playlist-modify-public",
                isPublicPlaylist = false
            )
        )
    }

    @Test
    fun privateModifyScopeAllowsDefaultPrivatePlaylists() {
        assertEquals(
            null,
            SpotifyScopePolicy.missingPlaylistModifyScope(
                scopes = "user-read-private playlist-modify-private",
                isPublicPlaylist = false
            )
        )
    }

    @Test
    fun publicPlaylistsRequirePublicModifyScope() {
        assertEquals(
            "playlist-modify-public",
            SpotifyScopePolicy.missingPlaylistModifyScope(
                scopes = "user-read-private playlist-modify-private",
                isPublicPlaylist = true
            )
        )
    }

    @Test
    fun detectsMissingLibraryModifyScope() {
        assertEquals(
            "user-library-modify",
            SpotifyScopePolicy.missingScope(
                scopes = "user-library-read user-top-read",
                requiredScope = "user-library-modify"
            )
        )
    }

    @Test
    fun detectsMissingTopItemsScope() {
        assertEquals(
            "user-top-read",
            SpotifyScopePolicy.missingScope(
                scopes = "user-library-read user-library-modify",
                requiredScope = "user-top-read"
            )
        )
    }

    @Test
    fun returnsNullWhenRequiredScopeIsGranted() {
        assertEquals(
            null,
            SpotifyScopePolicy.missingScope(
                scopes = "user-library-read user-library-modify user-top-read",
                requiredScope = "user-top-read"
            )
        )
    }
}
