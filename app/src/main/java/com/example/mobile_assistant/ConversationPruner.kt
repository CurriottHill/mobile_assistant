package com.example.mobile_assistant

import org.json.JSONObject

/**
 * Manages conversation history size to stay within the model's context window.
 *
 * Pruning pipeline (executed in order until under budget):
 * 1. Replace old screen dumps with one-line stubs (keep last [KEEP_RECENT_SCREENS])
 * 2. Collapse completed sub-task tool exchanges into summaries
 * 3. Drop oldest assistant+tool pairs as a last resort
 */
internal class ConversationPruner {

    companion object {
        private const val PRUNE_THRESHOLD = 100_000
        private const val CHARS_PER_TOKEN = 4

        private const val KEEP_RECENT_SCREENS = 3

        private const val MIN_COLLAPSIBLE_MESSAGES = 6
        private const val KEEP_RECENT_ROUNDS = 2
    }

    // ── Token estimation ────────────────────────────────────────────────

    private fun estimateTokens(messages: List<JSONObject>): Int {
        var chars = 0
        for (msg in messages) {
            chars += msg.toString().length
        }
        return chars / CHARS_PER_TOKEN
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Run before each API call. Prunes [history] in-place when the estimated
     * token count exceeds [PRUNE_THRESHOLD].
     */
    fun pruneIfNeeded(history: MutableList<JSONObject>) {
        val before = estimateTokens(history)
        if (before < PRUNE_THRESHOLD) return

        val screenDumpsReplaced = pruneOldScreenDumps(history)
        var subTasksCollapsed = 0
        if (estimateTokens(history) < PRUNE_THRESHOLD) return

        subTasksCollapsed = collapseCompletedSubTasks(history)
        if (estimateTokens(history) < PRUNE_THRESHOLD) return

        dropOldestExchanges(history)
    }

    // ── 1. Screen dump pruning ──────────────────────────────────────────

    /**
     * Keep only the last [KEEP_RECENT_SCREENS] successful read_screen results
     * intact. Replace older ones with a compact stub so the conversation
     * structure stays valid for the API.
     *
     * @return number of screen dumps replaced
     */
    private fun pruneOldScreenDumps(history: MutableList<JSONObject>): Int {
        val screenIndices = mutableListOf<Int>()
        for (i in history.indices) {
            if (isScreenDumpMessage(history[i])) {
                screenIndices.add(i)
            }
        }

        if (screenIndices.size <= KEEP_RECENT_SCREENS) return 0

        val toReplace = screenIndices.dropLast(KEEP_RECENT_SCREENS)
        for ((step, idx) in toReplace.withIndex()) {
            val msg = history[idx]
            val stub = JSONObject()
                .put("ok", true)
                .put("tool", AgentTooling.TOOL_READ_SCREEN)
                .put("summary", "[screen dump from step $step — omitted]")
            history[idx] = JSONObject()
                .put("role", "tool")
                .put("tool_call_id", msg.optString("tool_call_id", "tool_call"))
                .put("content", stub.toString())
        }
        return toReplace.size
    }

    private fun isScreenDumpMessage(msg: JSONObject): Boolean {
        if (msg.optString("role") != "tool") return false
        val content = runCatching { JSONObject(msg.optString("content")) }.getOrNull()
            ?: return false
        return content.optBoolean("ok", false)
                && AgentTooling.canonicalToolName(content.optString("tool")) == AgentTooling.TOOL_READ_SCREEN
                && content.has("screen")
    }

    // ── 2. Sub-task collapse ────────────────────────────────────────────

    /**
     * Find contiguous runs of assistant+tool pairs between user messages.
     * For runs longer than [MIN_COLLAPSIBLE_MESSAGES], collapse all but the
     * last [KEEP_RECENT_ROUNDS] rounds into a single summary message.
     *
     * @return number of sub-task runs collapsed
     */
    private fun collapseCompletedSubTasks(history: MutableList<JSONObject>): Int {
        val runs = findToolExchangeRuns(history)
        var collapsed = 0

        for (run in runs.asReversed()) {
            val runMessages = history.subList(run.first, run.last + 1)
            if (runMessages.size < MIN_COLLAPSIBLE_MESSAGES) continue

            val roundBoundaries = findRoundBoundaries(runMessages)
            if (roundBoundaries.size <= KEEP_RECENT_ROUNDS) continue

            val roundsToCollapse = roundBoundaries.size - KEEP_RECENT_ROUNDS
            val collapseEndLocal = roundBoundaries[roundsToCollapse]
            val collapseEndGlobal = run.first + collapseEndLocal

            val summary = buildCollapseSummary(history, run.first, collapseEndGlobal)

            val summaryMessage = JSONObject()
                .put("role", "assistant")
                .put("content", summary)

            val removeCount = collapseEndGlobal - run.first
            for (j in 0 until removeCount) {
                history.removeAt(run.first)
            }
            history.add(run.first, summaryMessage)
            collapsed++
        }
        return collapsed
    }

    /**
     * Returns index ranges (inclusive) of contiguous assistant/tool message
     * runs that sit between user (or system) messages.
     */
    private fun findToolExchangeRuns(history: List<JSONObject>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var runStart = -1

        for (i in history.indices) {
            val role = history[i].optString("role")
            val isExchangeMessage = role == "assistant" || role == "tool"

            if (isExchangeMessage) {
                if (runStart == -1) runStart = i
            } else {
                if (runStart != -1) {
                    runs.add(runStart until i)
                    runStart = -1
                }
            }
        }
        if (runStart != -1) {
            runs.add(runStart until history.size)
        }
        return runs
    }

    /**
     * Within a sub-list of assistant+tool messages, find the local indices
     * where each "round" starts. A round begins at each assistant message.
     */
    private fun findRoundBoundaries(messages: List<JSONObject>): List<Int> {
        val boundaries = mutableListOf<Int>()
        for (i in messages.indices) {
            if (messages[i].optString("role") == "assistant") {
                boundaries.add(i)
            }
        }
        return boundaries
    }

    private fun buildCollapseSummary(
        history: List<JSONObject>,
        fromInclusive: Int,
        toExclusive: Int
    ): String {
        val toolNames = mutableListOf<String>()
        for (i in fromInclusive until toExclusive) {
            val msg = history[i]
            if (msg.optString("role") != "tool") continue
            val content = runCatching { JSONObject(msg.optString("content")) }.getOrNull()
                ?: continue
            val tool = AgentTooling.canonicalToolName(content.optString("tool"))
            if (tool.isNotBlank()) toolNames.add(tool)
        }

        val distinctTools = toolNames.distinct()
        val toolSummary = if (distinctTools.size <= 4) {
            distinctTools.joinToString(", ")
        } else {
            distinctTools.take(3).joinToString(", ") + ", +${distinctTools.size - 3} more"
        }

        return "[Completed sub-task: $toolSummary — ${toolNames.size} tool calls collapsed]"
    }

    // ── 3. Emergency oldest-first drop ──────────────────────────────────

    /**
     * Drop the oldest assistant+tool exchanges (never the system prompt or
     * anything from the last user message onward) until under budget.
     */
    private fun dropOldestExchanges(history: MutableList<JSONObject>) {
        val lastUserIdx = history.indexOfLast { it.optString("role") == "user" }
        val safeUpperBound = if (lastUserIdx > 0) lastUserIdx else history.size

        while (estimateTokens(history) >= PRUNE_THRESHOLD && history.size > 2) {
            val dropIdx = findNextDroppableIndex(history, safeUpperBound)
            if (dropIdx == -1) break
            history.removeAt(dropIdx)
        }
    }

    /**
     * Find the first message after index 0 (system prompt) and before
     * [upperBound] that is an assistant or tool message.
     */
    private fun findNextDroppableIndex(history: List<JSONObject>, upperBound: Int): Int {
        for (i in 1 until upperBound.coerceAtMost(history.size)) {
            val role = history[i].optString("role")
            if (role == "assistant" || role == "tool") return i
        }
        return -1
    }

}
