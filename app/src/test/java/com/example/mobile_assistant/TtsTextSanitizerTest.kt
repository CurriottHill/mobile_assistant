package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextSanitizerTest {
    @Test
    fun sanitizeAgentText_removesLinksCitationsAndRangePunctuation() {
        val sanitized = TtsTextSanitizer.sanitizeAgentText(
            "See [this](https://example.com) at www.openai.com  for 50-75 results."
        )

        assertEquals("See this at for 50 to 75 results.", sanitized)
    }

    @Test
    fun sanitizeToolText_keepsTypicalOutputAlignedWithAgentSanitizer() {
        val raw = "Visit https://example.com or www.openai.com for 10-20 items."

        assertEquals(
            TtsTextSanitizer.sanitizeAgentText(raw),
            TtsTextSanitizer.sanitizeToolText(raw)
        )
    }
}
