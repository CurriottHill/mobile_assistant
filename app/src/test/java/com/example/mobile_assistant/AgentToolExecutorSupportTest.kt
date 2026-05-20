package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolExecutorSupportTest {
    @Test
    fun normalizeQuickQuestion_addsPrefixAndReplyHint() {
        assertEquals(
            "quick question: Which account should I use Reply in this chat.",
            AgentToolExecutorSupport.normalizeQuickQuestion("Which account should I use")
        )
    }

    @Test
    fun composeEmailDraftQuestion_invitesReadAloudOrSend() {
        assertEquals(
            "quick question: I've drafted an email to alex@example.com with subject line Hello and the message Just checking in. Would you like me to send it? Reply in this chat.",
            AgentToolExecutorSupport.composeEmailDraftQuestion(
                to = "alex@example.com",
                subject = "Hello",
                body = "Just checking in."
            )
        )
    }

    @Test
    fun shouldReusePendingEmailDraft_allowsMinimalSendConfirmation() {
        assertTrue(
            AgentToolExecutorSupport.shouldReusePendingEmailDraft(
                hasDraftPayload = false,
                confirmSend = false,
                hasPendingDraft = true
            )
        )
        assertTrue(
            AgentToolExecutorSupport.shouldReusePendingEmailDraft(
                hasDraftPayload = false,
                confirmSend = true,
                hasPendingDraft = false
            )
        )
        assertFalse(
            AgentToolExecutorSupport.shouldReusePendingEmailDraft(
                hasDraftPayload = true,
                confirmSend = true,
                hasPendingDraft = true
            )
        )
        assertFalse(
            AgentToolExecutorSupport.shouldReusePendingEmailDraft(
                hasDraftPayload = false,
                confirmSend = false,
                hasPendingDraft = false
            )
        )
    }

    @Test
    fun screenshotContentChanged_requiresTwoDifferentNonBlankImages() {
        assertTrue(
            AgentToolExecutorSupport.screenshotContentChanged(
                beforeDataUrl = "data:image/jpeg;base64,AAA",
                afterDataUrl = "data:image/jpeg;base64,BBB"
            )
        )
        assertFalse(
            AgentToolExecutorSupport.screenshotContentChanged(
                beforeDataUrl = "data:image/jpeg;base64,AAA",
                afterDataUrl = "data:image/jpeg;base64,AAA"
            )
        )
        assertFalse(
            AgentToolExecutorSupport.screenshotContentChanged(
                beforeDataUrl = null,
                afterDataUrl = "data:image/jpeg;base64,BBB"
            )
        )
    }

    @Test
    fun shouldAutoAttachObservation_skipsSharedApiTools() {
        assertFalse(AgentToolExecutorSupport.shouldAutoAttachObservation(SharedToolSchemas.TOOL_SEARCH_WEB))
        assertFalse(AgentToolExecutorSupport.shouldAutoAttachObservation(SharedToolSchemas.TOOL_CALL_CONTACT))
        assertFalse(AgentToolExecutorSupport.shouldAutoAttachObservation(SharedToolSchemas.TOOL_SPOTIFY_PLAY_SONG))
        assertFalse(AgentToolExecutorSupport.shouldAutoAttachObservation(SharedToolSchemas.TOOL_SPOTIFY_LIBRARY))
        assertFalse(AgentToolExecutorSupport.shouldAutoAttachObservation(SharedToolSchemas.TOOL_SPOTIFY_CONTROL_PLAYBACK))
        assertTrue(AgentToolExecutorSupport.shouldAutoAttachObservation(AgentTooling.TOOL_TAP_NODE))
    }
}
