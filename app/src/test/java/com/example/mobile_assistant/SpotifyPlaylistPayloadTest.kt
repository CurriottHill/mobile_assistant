package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistPayloadTest {

    @Test
    fun createBodyOmitsBlankDescription() {
        val body = SpotifyPlaylistPayloads.createBody("Eval Mix", isPublic = false, description = "  ")
        assertEquals("Eval Mix", body.getString("name"))
        assertFalse(body.getBoolean("public"))
        assertFalse(body.has("description"))
    }

    @Test
    fun createBodyKeepsDescriptionAndPublicFlag() {
        val body = SpotifyPlaylistPayloads.createBody("Road Trip", isPublic = true, description = "Long drives")
        assertTrue(body.getBoolean("public"))
        assertEquals("Long drives", body.getString("description"))
    }

    @Test
    fun addTracksBodyWrapsUrisArray() {
        val body = SpotifyPlaylistPayloads.addTracksBody(
            listOf("spotify:track:a", "spotify:track:b")
        )
        val uris = body.getJSONArray("uris")
        assertEquals(2, uris.length())
        assertEquals("spotify:track:a", uris.getString(0))
    }

    @Test
    fun removeTracksBodyUsesSpotifyTracksObjects() {
        val body = SpotifyPlaylistPayloads.removeTracksBody(
            listOf("spotify:track:a", "spotify:track:b")
        )
        val tracks = body.getJSONArray("tracks")
        assertEquals(2, tracks.length())
        assertEquals("spotify:track:a", tracks.getJSONObject(0).getString("uri"))
    }

    @Test
    fun updateDetailsBodyIncludesOnlyProvidedFields() {
        val body = SpotifyPlaylistPayloads.updateDetailsBody(
            name = "Highway Mix",
            description = "",
            descriptionSet = true,
            isPublic = false
        )
        assertEquals("Highway Mix", body.getString("name"))
        assertEquals("", body.getString("description"))
        assertFalse(body.getBoolean("public"))
    }

    @Test
    fun reorderBodyMatchesSpotifyUpdateItemsShape() {
        val body = SpotifyPlaylistPayloads.reorderBody(
            rangeStart = 2,
            insertBefore = 8,
            rangeLength = 3
        )
        assertEquals(2, body.getInt("range_start"))
        assertEquals(8, body.getInt("insert_before"))
        assertEquals(3, body.getInt("range_length"))
    }

    @Test
    fun chunkUrisSplitsAtHundred() {
        val uris = (1..101).map { "spotify:track:$it" }
        val chunks = SpotifyPlaylistPayloads.chunkUris(uris, 100)
        assertEquals(2, chunks.size)
        assertEquals(100, chunks[0].size)
        assertEquals(1, chunks[1].size)
    }

    @Test
    fun chunkUrisEmptyForNoInput() {
        assertTrue(SpotifyPlaylistPayloads.chunkUris(emptyList(), 100).isEmpty())
    }

    @Test
    fun createPlaylistEndpointUsesMePlaylistPath() {
        assertEquals(
            "https://api.spotify.com/v1/me/playlists",
            SpotifyPlaylistEndpoints.createPlaylistUrl("https://api.spotify.com/v1")
        )
    }

    @Test
    fun createPlaylistEndpointTrimsTrailingSlash() {
        assertEquals(
            "https://api.spotify.com/v1/me/playlists",
            SpotifyPlaylistEndpoints.createPlaylistUrl("https://api.spotify.com/v1/")
        )
    }

    @Test
    fun addItemsEndpointUsesCurrentSpotifyPath() {
        assertEquals(
            "https://api.spotify.com/v1/playlists/abc123/items",
            SpotifyPlaylistEndpoints.addItemsUrl("https://api.spotify.com/v1", "abc123")
        )
    }

    @Test
    fun playlistItemsEndpointUsesCurrentSpotifyPath() {
        assertEquals(
            "https://api.spotify.com/v1/playlists/abc123/items",
            SpotifyPlaylistEndpoints.itemsUrl("https://api.spotify.com/v1", "abc123")
        )
    }

    @Test
    fun playlistDetailsEndpointUsesPlaylistPath() {
        assertEquals(
            "https://api.spotify.com/v1/playlists/abc123",
            SpotifyPlaylistEndpoints.detailsUrl("https://api.spotify.com/v1", "abc123")
        )
    }

    @Test
    fun reorderEndpointUsesSpotifyPlaylistTracksPath() {
        assertEquals(
            "https://api.spotify.com/v1/playlists/abc123/tracks",
            SpotifyPlaylistEndpoints.updateItemsUrl("https://api.spotify.com/v1", "abc123")
        )
    }

    @Test
    fun libraryPayloadWrapsIds() {
        val body = SpotifyLibraryPayloads.idsBody(listOf("a", "b"))
        assertEquals("a", body.getJSONArray("ids").getString(0))
        assertEquals(2, body.getJSONArray("ids").length())
    }

    @Test
    fun libraryChunkIdsSplitsAtFifty() {
        val ids = (1..51).map { "$it" }
        val chunks = SpotifyLibraryPayloads.chunkIds(ids, 50)
        assertEquals(2, chunks.size)
        assertEquals(50, chunks[0].size)
        assertEquals(1, chunks[1].size)
    }

    @Test
    fun libraryEndpointsUseItemTypePaths() {
        assertEquals(
            "https://api.spotify.com/v1/me/tracks",
            SpotifyLibraryEndpoints.itemsUrl("https://api.spotify.com/v1", "track")
        )
        assertEquals(
            "https://api.spotify.com/v1/me/albums/contains",
            SpotifyLibraryEndpoints.containsUrl("https://api.spotify.com/v1", "album")
        )
    }

    @Test
    fun reorderValidationRejectsInvalidValues() {
        assertEquals("range_start must be zero or greater.", SpotifyPlaybackValidation.validateReorder(-1, 2, 1))
        assertEquals("insert_before must be zero or greater.", SpotifyPlaybackValidation.validateReorder(1, -2, 1))
        assertEquals("range_length must be between 1 and 100.", SpotifyPlaybackValidation.validateReorder(1, 2, 0))
        assertEquals(null, SpotifyPlaybackValidation.validateReorder(1, 2, 1))
    }

    @Test
    fun reorderInputValidationAllowsSongReferences() {
        assertEquals(
            null,
            SpotifyPlaybackValidation.validateReorderInputs(
                rangeStart = -1,
                insertBefore = -1,
                rangeLength = 1,
                trackQuery = "Dreams by Fleetwood Mac",
                songUri = null,
                beforeTrackQuery = null,
                beforeSongUri = null,
                destination = "top"
            )
        )
    }

    @Test
    fun reorderInputValidationRequiresMoveReferenceOrIndex() {
        assertEquals(
            "Provide either range_start or a specific song reference to move.",
            SpotifyPlaybackValidation.validateReorderInputs(
                rangeStart = -1,
                insertBefore = 0,
                rangeLength = 1,
                trackQuery = null,
                songUri = null,
                beforeTrackQuery = null,
                beforeSongUri = null,
                destination = null
            )
        )
    }

    @Test
    fun reorderInputValidationRequiresDestinationReferenceOrIndex() {
        assertEquals(
            "Provide either insert_before, destination, or a target song reference.",
            SpotifyPlaybackValidation.validateReorderInputs(
                rangeStart = 0,
                insertBefore = -1,
                rangeLength = 1,
                trackQuery = null,
                songUri = null,
                beforeTrackQuery = null,
                beforeSongUri = null,
                destination = null
            )
        )
    }

    @Test
    fun playbackValidationRejectsInvalidOptions() {
        assertEquals("Invalid Spotify playback action.", SpotifyPlaybackValidation.validateAction("stop"))
        assertEquals(null, SpotifyPlaybackValidation.validateAction("next"))
        assertEquals("volume_percent must be between 0 and 100.", SpotifyPlaybackValidation.validateVolume(101))
        assertEquals(null, SpotifyPlaybackValidation.validateVolume(55))
        assertEquals("repeat_mode must be off, track, or context.", SpotifyPlaybackValidation.validateRepeatMode("album"))
        assertEquals(null, SpotifyPlaybackValidation.validateRepeatMode("context"))
    }
}
