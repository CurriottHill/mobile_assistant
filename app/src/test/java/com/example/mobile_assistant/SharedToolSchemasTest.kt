package com.example.mobile_assistant

import org.junit.Assert.assertTrue
import org.junit.Test

class SharedToolSchemasTest {
    @Test
    fun spotifyTools_areMarkedAsSharedTools() {
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SEARCH_WEB))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_CALL_CONTACT))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_PLAY_ALBUM))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_PLAY_PLAYLIST))
        assertTrue(SharedToolSchemas.isSharedTool(SharedToolSchemas.TOOL_SPOTIFY_LIST_PLAYLISTS))
    }
}
