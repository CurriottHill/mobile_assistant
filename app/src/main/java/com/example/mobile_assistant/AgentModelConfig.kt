package com.example.mobile_assistant

enum class ModelProvider {
    OPENAI,
    ANTHROPIC
}

data class ModelSelection(
    val provider: ModelProvider,
    val model: String
)

object AgentModelConfig {
    // Change this one block to switch the phone agent to a different provider/model.
    val ACTIVE = ModelSelection(
        provider = ModelProvider.ANTHROPIC,
        model = "claude-haiku-4-5"
    )
}
