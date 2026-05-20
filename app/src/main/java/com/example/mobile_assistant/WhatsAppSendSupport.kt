package com.example.mobile_assistant

import java.util.Locale

/**
 * Pure, Android-free helpers for the WhatsApp send tool so the path decision and the
 * voice-phrasing normalization are unit-testable (same pattern as [MapsNavigationUrls],
 * [GoogleQueryBuilder], [ClockToolState]).
 */
internal object WhatsAppSendSupport {

    enum class Path { PHONE, SHARE_PICKER }

    /**
     * No phone match -> drive WhatsApp's native "Send to" picker (works for groups and
     * chats not in contacts). A resolved phone match keeps the fast wa.me path.
     */
    fun choosePath(resolved: ContactPhoneMatch?): Path =
        if (resolved == null) Path.SHARE_PICKER else Path.PHONE

    private val LEADING_FILLER = setOf("whatsapp", "the", "my", "our", "a", "to")
    private val TRAILING_FILLER = setOf("group", "chat", "groupchat", "conversation", "thread")

    /**
     * Reduces spoken phrasing like "the WhatsApp Family group chat" to the core chat title
     * "family" so the picker search can match it. Conservative: if stripping would empty the
     * query, the trimmed lowercased original is returned instead.
     */
    fun normalizeChatQuery(raw: String): String {
        val collapsed = raw.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return collapsed
        val tokens = collapsed.split(' ').toMutableList()

        while (tokens.size > 1 && tokens.first() in LEADING_FILLER) {
            tokens.removeAt(0)
        }
        while (tokens.size > 1 && tokens.last() in TRAILING_FILLER) {
            tokens.removeAt(tokens.size - 1)
        }

        val result = tokens.joinToString(" ").trim()
        return result.ifBlank { collapsed }
    }
}
