package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTrackSearchSupportTest {
    @Test
    fun parseQuery_extractsTitleAndArtistFromByPhrase() {
        val parsed = SpotifyTrackSearchSupport.parseQuery("Billie Jean by Michael Jackson")

        assertEquals("Billie Jean", parsed.title)
        assertEquals("Michael Jackson", parsed.artist)
        assertEquals("track:\"Billie Jean\" artist:\"Michael Jackson\"", parsed.searchQuery)
    }

    @Test
    fun parseQuery_extractsTitleAndArtistFromDashPhrase() {
        val parsed = SpotifyTrackSearchSupport.parseQuery("Everybody Wants To Rule The World - Tears For Fears")

        assertEquals("Everybody Wants To Rule The World", parsed.title)
        assertEquals("Tears For Fears", parsed.artist)
    }

    @Test
    fun matchScore_requiresRequestedArtistWhenPresent() {
        val score = SpotifyTrackSearchSupport.matchScore(
            requestedTitle = "Billie Jean",
            requestedArtist = "Michael Jackson",
            candidateTitle = "Billie Jean",
            candidateArtists = listOf("The Bates")
        )

        assertEquals(0, score)
    }

    @Test
    fun matchScore_acceptsExactTitleAndArtist() {
        val score = SpotifyTrackSearchSupport.matchScore(
            requestedTitle = "Billie Jean",
            requestedArtist = "Michael Jackson",
            candidateTitle = "Billie Jean",
            candidateArtists = listOf("Michael Jackson")
        )

        assertTrue(score >= SpotifyTrackSearchSupport.MIN_ACCEPTABLE_SCORE)
    }

    @Test
    fun parseQuery_keepsTitleOnlyQueriesTitleOnly() {
        val parsed = SpotifyTrackSearchSupport.parseQuery("Dreams")

        assertEquals("Dreams", parsed.title)
        assertNull(parsed.artist)
        assertEquals("Dreams", parsed.searchQuery)
    }
}
