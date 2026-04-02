package com.example.mobile_assistant

internal object TtsTextSanitizer {
    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val HTTP_URL_REGEX = Regex("""https?://[^\s)}\]]*""", RegexOption.IGNORE_CASE)
    private val BARE_DOMAIN_REGEX = Regex("""\b[a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}(?:\.[a-zA-Z]{2,})*(?:/[^\s)}\]]*)?""")
    private val CITATION_REGEX = Regex("""\u3010[^】]*\u3011""")
    private val NUMBER_RANGE_REGEX = Regex("""(\d)\s*[-–—]\s*(\d)""")
    private val EMPTY_PARENS_REGEX = Regex("""\(\s*\)""")
    private val WHITESPACE_REGEX = Regex("""\s{2,}""")

    private val AGENT_WWW_REGEX = Regex("""www\.[^\s)}\]]*""", RegexOption.IGNORE_CASE)
    private val TOOL_WWW_REGEX = Regex("""www\.[ hello^\s)}\]]*""", RegexOption.IGNORE_CASE)

    fun sanitizeAgentText(text: String): String {
        return sanitize(text, AGENT_WWW_REGEX)
    }

    fun sanitizeToolText(text: String): String {
        return sanitize(text, TOOL_WWW_REGEX)
    }

    private fun sanitize(text: String, wwwRegex: Regex): String {
        var sanitized = text
        sanitized = sanitized.replace(MARKDOWN_LINK_REGEX, "$1")
        sanitized = sanitized.replace(HTTP_URL_REGEX, "")
        sanitized = sanitized.replace(wwwRegex, "")
        sanitized = sanitized.replace(BARE_DOMAIN_REGEX, "")
        sanitized = sanitized.replace(CITATION_REGEX, "")
        sanitized = sanitized.replace(NUMBER_RANGE_REGEX, "$1 to $2")
        sanitized = sanitized.replace(EMPTY_PARENS_REGEX, "")
        sanitized = sanitized.replace(WHITESPACE_REGEX, " ").trim()
        return sanitized
    }
}
