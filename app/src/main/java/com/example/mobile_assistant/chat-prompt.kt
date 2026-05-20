package com.example.mobile_assistant

import org.json.JSONArray
import org.json.JSONObject

internal object ChatPrompt {
    const val MODEL = AgentModelConfig.CHAT_MODEL
    const val TOOL_USE_PHONE = "use_phone"
    const val TOOL_OPEN_APP = AgentTooling.TOOL_OPEN_APP
    const val TOOL_OPEN_NOTIFICATIONS = AgentTooling.TOOL_OPEN_NOTIFICATIONS
    const val TOOL_ASK_USER = AgentTooling.TOOL_ASK_USER

    private data class BuiltInToolSpec(
        val type: String
    )

    private val builtInTools = emptyList<BuiltInToolSpec>()

    private val chatOnlyFunctionTools = listOf(
        FunctionToolSchema(
            name = TOOL_OPEN_APP,
            description = "Open an installed app by lowercase app name. If no installed app matches, this tool fails.",
            properties = mapOf(
                "name" to stringToolProperty("Lowercase app name, for example 'spotify'.")
            ),
            required = listOf("name")
        ),
        FunctionToolSchema(
            name = TOOL_OPEN_NOTIFICATIONS,
            description = "Open the Android notification shade so the user's notifications are visible on screen. Use this for requests like 'open notifications' or 'pull down my notifications'.",
            properties = emptyMap()
        ),
        FunctionToolSchema(
            name = TOOL_ASK_USER,
            description = "Ask the user a question only when critical information is missing and you cannot continue safely. Do not use this to reconfirm a clear request. The question will be spoken aloud via TTS.",
            properties = mapOf(
                "question" to stringToolProperty("Question for the user.")
            ),
            required = listOf("question")
        ),
        FunctionToolSchema(
            name = TOOL_USE_PHONE,
            description = "Hand the task off to the phone agent. Call this when the user wants you to do something on their phone that cannot be handled by a direct tool.",
            properties = mapOf(
                "goal" to stringToolProperty(
                    "The single, resolved task goal to perform on the phone. Read the conversation history (including any prior use_phone tags) and decide whether the user's latest message is a brand-new goal or a continuation/clarification of an existing one — merge accordingly into ONE clear imperative goal. Fix likely speech-to-text errors. Do not echo the raw transcript; pass the cleaned, resolved goal."
                )
            ),
            required = listOf("goal")
        )
    )

    fun instructions(): String = buildString {
        appendLine(PromptClock.promptContextHeader())
        appendLine()
        append(systemPromptBody())
    }

    fun buildTools(): JSONArray {
        return JSONArray().also { tools ->
            builtInTools.forEach { spec ->
                tools.put(org.json.JSONObject().put("type", spec.type))
            }
            SharedToolSchemas.chatFunctionTools().forEach { spec ->
                tools.put(spec.toJson())
            }
            chatOnlyFunctionTools.forEach { spec ->
                tools.put(spec.toJson())
            }
        }
    }

    fun buildAnthropicTools(): JSONArray {
        val openAiTools = buildTools()
        return JSONArray().also { out ->
            for (i in 0 until openAiTools.length()) {
                val tool = openAiTools.optJSONObject(i) ?: continue
                if (tool.optString("type") != "function") continue
                val params = tool.optJSONObject("parameters") ?: JSONObject()
                    .put("type", "object").put("properties", JSONObject())
                out.put(
                    JSONObject()
                        .put("name", tool.optString("name"))
                        .put("description", tool.optString("description"))
                        .put("input_schema", params)
                )
            }
        }
    }

    fun findUsePhoneGoal(toolCalls: JSONArray?): String? {
        return findStringFunctionArgument(
            toolCalls = toolCalls,
            toolName = TOOL_USE_PHONE,
            argumentName = "goal"
        )
    }

    private fun systemPromptBody(): String {
        return """
You are a helpful voice assistant on the user's Android phone. Your responses are spoken aloud via TTS.

For anything requiring current information, news, or live facts, call search_web. For simple conversational questions you already know, answer directly.

Only use the use_phone tool for tasks that cannot be completed with a direct tool call.

For everything else — multi-step flows, WhatsApp, Maps navigation, Spotify playlist management, email, calendar, timers, alarms, or phone settings — call use_phone.

MANDATORY OUTPUT FORMAT. Every word you output will be read aloud. You must follow these rules in all responses:
1. Never include URLs, links, or web addresses.
2. Never use hyphens or dashes between numbers or words. Write 50 to 75 million, not 50 dash 75 million. Write well known, not well-known.
3. Never use special characters that sound bad in TTS. No bullet points, asterisks, brackets, slashes, or markdown formatting.
4. Use numeric digits for numbers, not words. Write 4.35, not four point three five. Write 50, not fifty.
5. Never write number ranges with a hyphen or dash. Always use to.
6. Keep responses concise and conversational. For simple questions, answer in 2 to 4 sentences.

OTHER RULES
Use ask_user only when genuinely blocked on missing critical information. Do not use ask_user to reconfirm a clear request, including routine Spotify or playlist edits the user already asked for.
Chat mode may use direct tools in a short loop. After each tool result, decide whether another direct tool is needed. End the loop only when you have a final spoken reply, need ask_user, or need use_phone.
When you call use_phone, you MUST supply `goal`. Read the conversation history (including earlier use_phone tags and their results) and decide whether the user's latest message is a brand-new request or a continuation/clarification of the existing task — merge them into one clear imperative goal. Correct obvious speech-to-text errors. Do not echo the raw user transcript; pass the cleaned, resolved goal.
If a tool reports missing access with needs_permission, needs_location_enabled, listener_enabled false, needs_connect, or needs_reconnect, say briefly that access is needed and stop. Do not explain where to tap in Settings.
Never open a banking app

""".trimIndent()
    }
}
