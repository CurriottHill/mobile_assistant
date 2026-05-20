package com.example.mobile_assistant

object AgentModelConfig {
    // The phone agent runs on Anthropic Sonnet by default; quick chat replies stay on Haiku.
    const val CHAT_MODEL = "claude-haiku-4-5-20251001"
    const val SONNET_MODEL = "claude-sonnet-4-6"
    const val DEFAULT_AGENT_MODEL = SONNET_MODEL
    const val OPENAI_HAIKU_FALLBACK_MODEL = "gpt-5-mini"
    const val OPENAI_SONNET_FALLBACK_MODEL = "gpt-5.4"
    const val OPENAI_WEB_SEARCH_MODEL = "gpt-5-search-api"
    const val OPENAI_AGENT_REASONING_EFFORT = "medium"

    // Model used for eval runs + the vision judge (Haiku for everything in evals).
    const val EVAL_HAIKU_MODEL = "claude-haiku-4-5-20251001"

    /**
     * Runtime override for the agent model. The eval screen sets this so a run can be
     * tagged/compared (Haiku vs Sonnet); null restores normal behavior. @Volatile because
     * it is written from the eval Activity and read from the agent loop coroutine.
     */
    @Volatile
    var agentModelOverride: String? = null

    val AGENT_MODEL: String
        get() = agentModelOverride ?: DEFAULT_AGENT_MODEL

    fun openAiFallbackModelFor(anthropicModel: String): String {
        return when {
            anthropicModel.contains("haiku", ignoreCase = true) -> OPENAI_HAIKU_FALLBACK_MODEL
            anthropicModel.contains("sonnet", ignoreCase = true) -> OPENAI_SONNET_FALLBACK_MODEL
            else -> OPENAI_SONNET_FALLBACK_MODEL
        }
    }

    fun openAiWebSearchModelFor(openAiModel: String): String {
        return when {
            openAiModel.contains("search", ignoreCase = true) -> openAiModel
            else -> OPENAI_WEB_SEARCH_MODEL
        }
    }
}
