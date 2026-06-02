package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal class ConversationHistory {

    enum class Kind { USER, ASSISTANT, REASONING, TOOL_CALL }

    data class Entry(
        val kind: Kind,
        val text: String,
        val toolName: String? = null,
        val toolArgsDisplay: String? = null,
        val toolResultDisplay: String? = null
    )

    private val entries = ArrayList<Entry>()
    private val lock = Any()

    fun addUser(text: String) = appendIfNotBlank(Entry(Kind.USER, text.trim()))
    fun addAssistant(text: String) = appendIfNotBlank(Entry(Kind.ASSISTANT, text.trim()))
    fun addReasoning(text: String) = appendIfNotBlank(Entry(Kind.REASONING, text.trim()))

    fun addToolCall(toolName: String, argsDisplay: String, resultDisplay: String) {
        val name = toolName.trim()
        if (name.isBlank()) return
        synchronized(lock) {
            entries.add(
                Entry(
                    kind = Kind.TOOL_CALL,
                    text = renderToolCallText(name, argsDisplay, resultDisplay),
                    toolName = name,
                    toolArgsDisplay = argsDisplay,
                    toolResultDisplay = resultDisplay
                )
            )
            prune()
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    private fun appendIfNotBlank(entry: Entry) {
        if (entry.text.isBlank()) return
        synchronized(lock) {
            entries.add(entry)
            prune()
        }
    }

    /**
     * Keep the last [MAX_USER_MESSAGES] USER entries and every entry at/after the
     * Nth-from-last USER index. Older entries are dropped.
     */
    private fun prune() {
        val userIndices = entries.indices.filter { entries[it].kind == Kind.USER }
        if (userIndices.size <= MAX_USER_MESSAGES) return
        val firstKeepIndex = userIndices[userIndices.size - MAX_USER_MESSAGES]
        if (firstKeepIndex > 0) {
            entries.subList(0, firstKeepIndex).clear()
        }
    }

    /**
     * Anthropic chat messages: alternating user/assistant text. REASONING and TOOL_CALL
     * collapse into assistant-role text alongside ASSISTANT entries. Consecutive same-role
     * entries are coalesced with `\n\n`. Drops a leading assistant message if present.
     */
    fun toAnthropicChatMessages(): JSONArray = synchronized(lock) {
        val messages = JSONArray()
        var pendingRole: String? = null
        val pendingText = StringBuilder()

        fun flush() {
            val role = pendingRole ?: return
            messages.put(JSONObject().put("role", role).put("content", pendingText.toString()))
            pendingRole = null
            pendingText.setLength(0)
        }

        for (entry in entries) {
            val role = if (entry.kind == Kind.USER) "user" else "assistant"
            val text = renderForApi(entry)
            if (text.isBlank()) continue
            if (role == pendingRole) {
                pendingText.append("\n\n")
            } else {
                flush()
                pendingRole = role
            }
            pendingText.append(text)
        }
        flush()

        if (messages.length() > 0 && messages.optJSONObject(0)?.optString("role") == "assistant") {
            messages.remove(0)
        }
        messages
    }

    /**
     * OpenAI chat messages: leading system message, then alternating user/assistant text
     * mirroring [toAnthropicChatMessages]. No native tool_calls — historical tool calls
     * are rendered as readable tag text on assistant turns.
     */
    fun toOpenAiChatMessages(systemPrompt: String): JSONArray = synchronized(lock) {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))

        var pendingRole: String? = null
        val pendingText = StringBuilder()

        fun flush() {
            val role = pendingRole ?: return
            messages.put(JSONObject().put("role", role).put("content", pendingText.toString()))
            pendingRole = null
            pendingText.setLength(0)
        }

        for (entry in entries) {
            val role = if (entry.kind == Kind.USER) "user" else "assistant"
            val text = renderForApi(entry)
            if (text.isBlank()) continue
            if (role == pendingRole) {
                pendingText.append("\n\n")
            } else {
                flush()
                pendingRole = role
            }
            pendingText.append(text)
        }
        flush()
        messages
    }

    /**
     * Single readable transcript block for the agent. Always returns a string (header
     * plus body). Empty body becomes "(none)" so the agent always sees the section.
     */
    fun toHistoryBlockText(): String = synchronized(lock) {
        buildString {
            appendLine("## Conversation History")
            appendLine("Rolling window of the last $MAX_USER_MESSAGES user messages and every assistant/agent/tool entry in between.")
            appendLine()
            if (entries.isEmpty()) {
                append("(none)")
                return@buildString
            }
            entries.forEachIndexed { index, entry ->
                when (entry.kind) {
                    Kind.USER -> appendLine("User: ${entry.text}")
                    Kind.ASSISTANT -> appendLine("Assistant: ${entry.text}")
                    Kind.REASONING -> appendLine("Reasoning: ${entry.text}")
                    Kind.TOOL_CALL -> appendLine(entry.text)
                }
                if (index < entries.lastIndex) appendLine()
            }
        }.trimEnd()
    }

    private fun renderForApi(entry: Entry): String = when (entry.kind) {
        Kind.USER -> entry.text
        Kind.ASSISTANT -> entry.text
        Kind.REASONING -> "Reasoning: ${entry.text}"
        Kind.TOOL_CALL -> entry.text
    }

    private fun renderToolCallText(name: String, args: String, result: String): String {
        val argsPart = if (args.isBlank()) "" else " $args"
        val resultPart = if (result.isBlank()) "" else " -> $result"
        return "[tool: $name$argsPart]$resultPart"
    }

    companion object {
        const val MAX_USER_MESSAGES = 8
        const val MAX_RESULT_DISPLAY_CHARS = 1500

        private val HEAVY_KEYS = setOf("screen", "tree", "image_data_url", "image", "screenshot", "base64")

        /**
         * Compact one-line JSON for tool args, stripping heavy keys (`screen`, `tree`,
         * `image_data_url`, base64 blobs). Non-JSON args are returned truncated.
         */
        fun formatToolArgsForDisplay(toolName: String, rawArgs: String): String {
            if (rawArgs.isBlank()) return ""
            return runCatching {
                val obj = JSONObject(rawArgs)
                stripHeavyKeys(obj)
                truncateInPlace(obj)
                obj.toString()
            }.getOrElse { rawArgs.take(MAX_RESULT_DISPLAY_CHARS / 4) }
        }

        /**
         * Compact one-line summary of a tool result for storage in history.
         * - `read_screen` (and any screen-capture tool) returns ONLY a tiny stub
         *   like `[screen: <package>, rev <n>]` — never the raw tree or screenshot.
         * - Other tools return the result JSON/text truncated, with heavy keys stripped.
         */
        fun formatToolResultForHistory(toolName: String, rawResult: String): String {
            if (rawResult.isBlank()) return ""
            val asJson = runCatching { JSONObject(rawResult) }.getOrNull()

            if (toolName == AgentTooling.TOOL_READ_SCREEN) {
                val pkg = asJson?.optString("package_name").orEmpty()
                val rev = asJson?.opt("image_revision")?.toString().orEmpty()
                val parts = buildList {
                    if (pkg.isNotBlank()) add("package=$pkg")
                    if (rev.isNotBlank() && rev != "null") add("rev=$rev")
                }
                val tail = if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
                return "[screen$tail]"
            }

            return if (asJson != null) {
                stripHeavyKeys(asJson)
                truncateInPlace(asJson)
                val text = asJson.toString()
                if (text.length > MAX_RESULT_DISPLAY_CHARS) text.take(MAX_RESULT_DISPLAY_CHARS) + "…" else text
            } else {
                if (rawResult.length > MAX_RESULT_DISPLAY_CHARS) rawResult.take(MAX_RESULT_DISPLAY_CHARS) + "…" else rawResult
            }
        }

        private fun stripHeavyKeys(obj: JSONObject) {
            val keys = obj.keys().asSequence().toList()
            for (key in keys) {
                if (HEAVY_KEYS.contains(key)) {
                    obj.put(key, "[omitted]")
                    continue
                }
                when (val v = obj.opt(key)) {
                    is JSONObject -> stripHeavyKeys(v)
                    is JSONArray -> stripHeavyKeysArray(v)
                }
            }
        }

        private fun stripHeavyKeysArray(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                when (val v = arr.opt(i)) {
                    is JSONObject -> stripHeavyKeys(v)
                    is JSONArray -> stripHeavyKeysArray(v)
                }
            }
        }

        private fun truncateInPlace(obj: JSONObject) {
            val keys = obj.keys().asSequence().toList()
            for (key in keys) {
                when (val v = obj.opt(key)) {
                    is String -> if (v.length > 400) obj.put(key, v.take(400) + "…")
                    is JSONObject -> truncateInPlace(v)
                    is JSONArray -> truncateInPlaceArray(v)
                }
            }
        }

        private fun truncateInPlaceArray(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                when (val v = arr.opt(i)) {
                    is String -> if (v.length > 400) arr.put(i, v.take(400) + "…")
                    is JSONObject -> truncateInPlace(v)
                    is JSONArray -> truncateInPlaceArray(v)
                }
            }
        }
    }
}
