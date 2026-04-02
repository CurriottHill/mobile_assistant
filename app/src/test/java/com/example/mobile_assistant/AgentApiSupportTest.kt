package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentApiSupportTest {
    @Test
    fun stripCitationMarkers_removesInlineCitationsAndCompactsWhitespace() {
        assertEquals(
            "Answer with extra spacing.",
            AgentApiSupport.stripCitationMarkers("Answer \u30104:0\u2020source\u3011 with   extra spacing.")
        )
    }

    @Test
    fun compactApiErrorBody_flattensWhitespaceAndTruncates() {
        assertEquals(
            "line one line",
            AgentApiSupport.compactApiErrorBody(" line one\n\nline two ", maxBodyChars = 13)
        )
    }
}
