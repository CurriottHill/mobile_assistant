# Architecture

Marvin is organized around a small number of runtime loops and focused tool services.

## Main Runtime Flow

1. `MainActivity` guides setup and checks whether required Android settings are enabled.
2. `AssistantActivity` and `AssistantAccessibilityService` launch the floating assistant experience.
3. `AssistantOverlayController` collects typed or spoken user input, updates overlay UI state, and coordinates transcription, TTS, and task lifecycle events.
4. `AgentManager` decides whether a request can be answered conversationally or should become a phone-control task.
5. Tool calls are routed into small service classes, then results are fed back into the agent loop until completion or cutoff.

## Important Classes

- `AgentManager`: LLM calls, model fallback paths, tool-call parsing, state tracking, model usage telemetry, and final response handling.
- `AgentTools` and `ToolSchemas`: shared tool registry and JSON schemas exposed to model providers.
- `SharedToolExecutor`: common execution path for tool calls.
- `AssistantOverlayController`: overlay UI, voice recording, transcription, TTS, and foreground task state.
- `AssistantAccessibilityService`: Android accessibility entrypoint.
- `ScreenReader`: UI tree extraction and node matching.
- `ScreenGestureDispatcher`, `ScreenSwipeDispatcher`, `ScreenPageScrollDispatcher`: low-level gesture execution.
- `ConversationHistory`: rolling chat state with provider-specific message formatting.
- `EvalActivity`, `EvalRecorder`, `EvalScoring`, `EvalTaskCatalog`: local evaluation support.

## Provider Configuration

The project supports several provider paths because the agent and chat layers have different latency, cost, and tool-use requirements:

- OpenRouter/DeepSeek for chat defaults.
- OpenRouter/Qwen for phone-agent defaults.
- Anthropic fallbacks for structured reasoning and eval judging.
- OpenAI fallbacks for direct chat, memory review, and web-search paths.

Keys are injected at build time from `local.properties` into `BuildConfig`. Public builds should leave unused keys blank.

## Optional Account Backend

`MobileBackendClient` is the only client for account summaries, task starts, task finishes, and model-usage reporting. It requires `MARVIN_API_BASE_URL`; when that value is blank, the app treats account backend features as unconfigured.

This keeps the portfolio build runnable without depending on a private hosted service.
