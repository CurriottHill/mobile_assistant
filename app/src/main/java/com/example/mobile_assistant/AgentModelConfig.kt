package com.example.mobile_assistant

object AgentModelConfig {
    // Chat uses OpenRouter/DeepSeek; the phone agent still uses OpenRouter/Qwen.
    const val DEEPSEEK_CHAT_MODEL = "deepseek/deepseek-v4-flash:free"
    const val DEEPSEEK_CHAT_BACKUP_MODEL = "deepseek/deepseek-v4-flash"
    const val QWEN_MODEL = "qwen/qwen3.7-max"
    const val CHAT_MODEL = DEEPSEEK_CHAT_MODEL
    const val ANTHROPIC_CHAT_BACKUP_MODEL = "claude-haiku-4-5-20251001"
    const val ANTHROPIC_AGENT_BACKUP_MODEL = "claude-sonnet-4-6"
    const val SONNET_MODEL = ANTHROPIC_AGENT_BACKUP_MODEL
    const val DEFAULT_AGENT_MODEL = QWEN_MODEL
    const val OPENAI_HAIKU_FALLBACK_MODEL = "gpt-5-mini"
    const val OPENAI_SONNET_FALLBACK_MODEL = "gpt-5.4"
    const val OPENAI_WEB_SEARCH_MODEL = "gpt-5-search-api"
    const val MEMORY_SAVE_MODEL = "gpt-5-mini"
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

    fun anthropicBackupModelFor(model: String): String {
        return when {
            model.contains("haiku", ignoreCase = true) -> ANTHROPIC_CHAT_BACKUP_MODEL
            model.contains("qwen", ignoreCase = true) -> ANTHROPIC_AGENT_BACKUP_MODEL
            else -> ANTHROPIC_AGENT_BACKUP_MODEL
        }
    }

    fun allowAnthropicChatFallbackFor(model: String): Boolean {
        return !model.contains("deepseek", ignoreCase = true)
    }

    fun deepSeekChatFallbackFor(model: String): String? {
        return if (model == DEEPSEEK_CHAT_MODEL) DEEPSEEK_CHAT_BACKUP_MODEL else null
    }

    fun openAiFallbackModelFor(model: String): String {
        return when {
            model.contains("haiku", ignoreCase = true) -> OPENAI_HAIKU_FALLBACK_MODEL
            model.contains("deepseek", ignoreCase = true) -> OPENAI_HAIKU_FALLBACK_MODEL
            model.contains("sonnet", ignoreCase = true) -> OPENAI_SONNET_FALLBACK_MODEL
            model.contains("qwen", ignoreCase = true) -> OPENAI_SONNET_FALLBACK_MODEL
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
