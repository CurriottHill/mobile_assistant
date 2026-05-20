package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppSendSupportTest {

    @Test
    fun choosePath_phoneWhenResolvedElseSharePicker() {
        assertEquals(
            WhatsAppSendSupport.Path.SHARE_PICKER,
            WhatsAppSendSupport.choosePath(null)
        )
        assertEquals(
            WhatsAppSendSupport.Path.PHONE,
            WhatsAppSendSupport.choosePath(
                ContactPhoneMatch(
                    displayName = "Mum",
                    phoneNumber = "+15551234567",
                    matchKind = "exact"
                )
            )
        )
    }

    @Test
    fun normalizeChatQuery_stripsVoicePhrasingToCoreTitle() {
        assertEquals("family", WhatsAppSendSupport.normalizeChatQuery("the WhatsApp Family group chat"))
        assertEquals("family", WhatsAppSendSupport.normalizeChatQuery("Family group"))
        assertEquals("family", WhatsAppSendSupport.normalizeChatQuery("  my   Family   chat "))
        assertEquals("mum", WhatsAppSendSupport.normalizeChatQuery("Mum"))
        assertEquals("book club", WhatsAppSendSupport.normalizeChatQuery("the Book Club groupchat"))
    }

    @Test
    fun normalizeChatQuery_neverEmptiesTheQuery() {
        // All-filler input must not collapse to empty; keep the trimmed lowercased original.
        assertEquals("group", WhatsAppSendSupport.normalizeChatQuery("group"))
        assertEquals("chat", WhatsAppSendSupport.normalizeChatQuery("Chat"))
        // Leading filler is stripped but the final token always survives (never empty).
        assertEquals("group", WhatsAppSendSupport.normalizeChatQuery("the group"))
    }

    @Test
    fun normalizeChatQuery_handlesBlank() {
        assertEquals("", WhatsAppSendSupport.normalizeChatQuery("   "))
    }
}
