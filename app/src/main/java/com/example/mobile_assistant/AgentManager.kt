package com.example.mobile_assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AgentManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val callbacks: AgentCallbacks,
    private val screenReader: () -> String?,
    private val usageTaskIdProvider: () -> String? = { null }
) {

    private enum class SearchProvider {
        ANTHROPIC,
        OPENAI
    }

    private data class SearchExecutionContext(
        val provider: SearchProvider,
        val model: String
    )

    private data class SharedChatToolResult(
        val toolName: String,
        val response: String,
        val resultContent: JSONObject,
        val uiAction: AssistantUiAction? = null,
        val isAskUser: Boolean = false
    )

    private data class ExecutedChatTool(
        val name: String,
        val rawArgs: String,
        val argsDisplay: String,
        val resultDisplay: String,
        val success: Boolean
    )

    private data class ToolCallTelemetry(
        val name: String,
        val rawArgs: String,
        val success: Boolean,
        val resultSummary: String
    )

    private sealed interface ChatLoopOutcome {
        val executedTools: List<ExecutedChatTool>

        data class Continue(
            val toolResults: JSONArray,
            override val executedTools: List<ExecutedChatTool>
        ) : ChatLoopOutcome
        data class FinalResponse(
            val response: String,
            val isAskUser: Boolean = false,
            val autoListen: Boolean = true,
            val toolResults: JSONArray? = null,
            override val executedTools: List<ExecutedChatTool> = emptyList()
        ) : ChatLoopOutcome
        data class HandoffToPhone(
            val task: String,
            val toolResults: JSONArray? = null,
            override val executedTools: List<ExecutedChatTool> = emptyList()
        ) : ChatLoopOutcome
    }

    interface AgentCallbacks {
        fun onAgentThinkingStarted(userMessage: String)
        fun onAgentThinkingFinished()
        fun onAgentResponse(response: String)
        fun onToolCalled(toolName: String, argsJson: String = "") {}
        fun onAgentReasoning(text: String) {}
        fun onAgentUiAction(action: AssistantUiAction) {}
        fun onAgentSpeak(message: String)
        fun onAgentTaskComplete(summary: String)
        fun onAgentAskUser(question: String, autoListen: Boolean = true)
        fun onAgentCutoff(reason: String, message: String) {
            onAgentResponse(message)
        }
        fun onAgentApiKeyMissing()
        fun onAgentError(message: String? = null)
        fun onAgentProviderTag(providerLabel: String) {}
        fun onAgentFallbackToOpenAi() {
            onAgentProviderTag("ChatGPT")
        }
    }

    private val chatClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val backendClient by lazy { MobileBackendClient(appContext) }

    private val spotifyService = SpotifyService(appContext)
    private val clockToolService = ClockToolService(appContext)
    private val callToolService = CallToolService(appContext)
    private val smsToolService = SmsToolService(appContext)
    private val mapsToolService = MapsToolService(appContext)
    private val googleAccountService = GoogleAccountService(appContext)
    private val whatsAppToolService = WhatsAppToolService(
        context = appContext,
        tapSendButton = {
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.tapWhatsAppSendButton(service)
            } ?: false
        },
        openWhatsAppShare = { message ->
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, message)
                        .setPackage("com.whatsapp")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
        },
        typeInPickerSearch = { query ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.setTextByViewIdOrFocused(
                    service,
                    "com.whatsapp:id/search_input",
                    query
                )
            } ?: false
        },
        tapChatRow = { name ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.tapChatRowByName(service, name)
            } ?: false
        }
    )
    private val phoneUtilToolService = PhoneUtilToolService(appContext)
    private val contactsToolService = ContactsToolService(appContext)
    private val deviceMediaToolService = DeviceMediaToolService(appContext)
    private val currentLocationToolService = CurrentLocationToolService(appContext)
    private val notificationToolService = NotificationToolService(appContext)
    private val weatherToolService = WeatherToolService(appContext)
    private val memoryRepository = MemoryRepository(appContext)
    private val memoryToolService = MemoryToolService(
        memoryRepository,
        OpenAiMemorySaveFactReviewer(onUsageRecorded = ::recordMemoryReviewUsage)
    )
    private val memorySaveLog = MemorySaveLog()
    private val genericMessagingToolService = GenericMessagingToolService(appContext)
    private val mapsTravelTimeToolService = MapsTravelTimeToolService(
        context = appContext,
        searchWeb = { query ->
            withContext(Dispatchers.IO) {
                performWebSearch(query)
            }
        }
    )
    private val calendarToolService = CalendarToolService(
        context = appContext,
        tapSendButton = { selectors ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.tapGmailSendButton(service) ||
                    ScreenReader.tapSendButton(service, selectors)
            } ?: false
        },
        sendEmailViaApi = { draft ->
            googleAccountService.sendEmail(
                to = draft.to,
                cc = draft.cc,
                bcc = draft.bcc,
                subject = draft.subject,
                body = draft.body
            )
        }
    )
    private val sharedToolExecutor = SharedToolExecutor(
        searchWeb = { query ->
            withContext(Dispatchers.IO) {
                performWebSearch(query)
            }
        },
        callToolService = callToolService,
        spotifyService = spotifyService,
        clockToolService = clockToolService,
        smsToolService = smsToolService,
        whatsAppToolService = whatsAppToolService,
        mapsToolService = mapsToolService,
        googleAccountService = googleAccountService,
        phoneUtilToolService = phoneUtilToolService,
        contactsToolService = contactsToolService,
        deviceMediaToolService = deviceMediaToolService,
        currentLocationToolService = currentLocationToolService,
        mapsTravelTimeToolService = mapsTravelTimeToolService,
        notificationToolService = notificationToolService,
        weatherToolService = weatherToolService,
        memoryToolService = memoryToolService,
        genericMessagingToolService = genericMessagingToolService,
        memoryRepository = memoryRepository,
        memorySaveLog = memorySaveLog
    )

    @Volatile
    private var activeSearchContext = SearchExecutionContext(
        provider = SearchProvider.ANTHROPIC,
        model = ChatPrompt.MODEL
    )

    private val toolExecutor = AgentToolExecutor(
        callbacks = callbacks,
        screenReader = screenReader,
        uiSignalReader = {
            AssistantAccessibilityService.instance?.currentUiSignal()
        },
        foregroundPackageReader = {
            val service = AssistantAccessibilityService.instance
            if (service == null) {
                null
            } else {
                val root = service.getUnderlyingAppRoot()
                if (root == null) {
                    null
                } else {
                    try {
                        root.packageName?.toString()?.trim()?.ifBlank { null }
                    } finally {
                        root.recycle()
                    }
                }
            }
        },
        appOpener = { appName ->
            AssistantAccessibilityService.instance?.let { service ->
                AppOpener.openApp(service, appName)
            } ?: AppOpenResult(
                opened = false,
                error = "Accessibility service is not connected."
            )
        },
        urlOpener = { url ->
            AssistantAccessibilityService.instance?.let { service ->
                AppOpener.openUrl(service, url)
            } ?: UrlOpenResult(
                opened = false,
                error = "Accessibility service is not connected."
            )
        },
        nodeTapper = { nodeRef ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.tapNode(service, nodeRef)
            } ?: NodeTapResult(
                tapped = false,
                error = "Accessibility service is not connected."
            )
        },
        nodeScroller = { nodeRef, direction ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.scrollNode(service, nodeRef, direction)
            } ?: NodeScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                error = "Accessibility service is not connected."
            )
        },
        screenObservationReader = {
            agentState.lastObservation
        },
        xyTapper = { point, expectedPackage ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenGestureDispatcher.tapFullScreenPoint(
                    service = service,
                    point = point,
                    expectedPackage = expectedPackage
                )
            } ?: CoordinateTapResult(
                tapped = false,
                xPx = point.x,
                yPx = point.y,
                error = "Accessibility service is not connected."
            )
        },
        swiper = { direction, expectedPackage ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenSwipeDispatcher.swipeInForegroundApp(
                    service = service,
                    direction = direction,
                    expectedPackage = expectedPackage
                )
            } ?: SwipeResult(
                swiped = false,
                direction = direction.wireValue,
                error = "Accessibility service is not connected."
            )
        },
        pageScroller = { direction, expectedPackage ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenPageScrollDispatcher.scrollPageInForegroundApp(
                    service = service,
                    direction = direction,
                    expectedPackage = expectedPackage
                )
            } ?: PageScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                error = "Accessibility service is not connected."
            )
        },
        nodeLongPresser = { nodeRef ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.longPressNode(service, nodeRef)
            } ?: NodeLongPressResult(
                pressed = false,
                error = "Accessibility service is not connected."
            )
        },
        tapAndTextTyper = { nodeRef, text ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.tapAndTypeText(service, nodeRef, text)
            } ?: NodeTapTypeTextResult(
                updated = false,
                tapped = false,
                error = "Accessibility service is not connected."
            )
        },
        textTyper = { nodeRef, text ->
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.typeText(service, nodeRef, text)
            } ?: NodeTextResult(
                updated = false,
                error = "Accessibility service is not connected."
            )
        },
        screenshotCapturer = {
            AssistantAccessibilityService.instance?.let { service ->
                ForegroundScreenshotter.capture(service)
            } ?: ScreenshotCaptureResult(
                ok = false,
                error = "Accessibility service is not connected."
            )
        },
        latestScreenshotDataUrlReader = {
            latestScreenshotDataUrl
        },
        goBack = {
            AssistantAccessibilityService.instance?.let { service ->
                val root = service.getUnderlyingAppRoot()
                val pkg = root?.packageName?.toString()?.trim()
                root?.recycle()

                val isBrowser = pkg != null && KNOWN_BROWSER_PACKAGES.any { pkg.contains(it) }
                val action = if (isBrowser) "browser_back" else "system_back"
                val pressed = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

                GoBackResult(
                    pressed = pressed,
                    action = action,
                    foregroundPackage = pkg
                )
            } ?: GoBackResult(
                pressed = false,
                action = "none",
                error = "Accessibility service is not connected."
            )
        },
        pressHome = {
            AssistantAccessibilityService.instance?.let { service ->
                val pressed = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                PressHomeResult(pressed = pressed)
            } ?: PressHomeResult(
                pressed = false,
                error = "Accessibility service is not connected."
            )
        },
        closeApp = {
            AssistantAccessibilityService.instance?.let { service ->
                AppCloser.closeCurrentForegroundApp(service)
            } ?: PressHomeResult(
                pressed = false,
                error = "Accessibility service is not connected."
            )
        },
        openRecents = {
            AssistantAccessibilityService.instance?.let { service ->
                val pressed = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                PressHomeResult(pressed = pressed)
            } ?: PressHomeResult(
                pressed = false,
                error = "Accessibility service is not connected."
            )
        },
        openNotifications = {
            AssistantAccessibilityService.instance?.let { service ->
                val pressed = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                PressHomeResult(pressed = pressed)
            } ?: PressHomeResult(
                pressed = false,
                error = "Accessibility service is not connected."
            )
        },
        calendarToolService = calendarToolService,
        sharedToolExecutor = sharedToolExecutor
    )
    private var chatJob: Job? = null
    private var lastAgentApiKey: String? = null
    private var isPaused = false
    private var latestUserMessage = ""
    private var capturedInitialScreenDump: String? = null
    private var latestScreenDump: String? = null
    private var latestScreenRevision: Long? = null
    private var latestScreenshotDataUrl: String? = null
    private var latestScreenshotRevision: Long? = null
    private var agentState = emptyAgentState()
    private val toolCallLog = mutableListOf<ToolCallSummary>()
    // Per-task eval transcript: populated for EvalRecorder.finalize only. NOT model-facing.
    // The model reads conversation history (see `conversation`) plus live screen state each step.
    private val evalTranscript = mutableListOf<ActionTranscriptEntry>()
    private val conversation = ConversationHistory()
    private val pendingTurnBlocks = mutableListOf<JSONObject>()
    private var lastUiChange = "none"
    private var lastSuccessfulToolName: String? = null
    private var lastTappedNodeRef: String? = null
    private var lastTappedLabel: String? = null
    private var consecutiveSameToolCount = 0
    private var lastToolCallSignature: String? = null
    private val displayedProviderTags = mutableSetOf<String>()

    fun sendToAgent(message: String) {
        val agentApiKey = agentProviderApiKey()
        if (agentApiKey.isBlank()) {
            callbacks.onAgentApiKeyMissing()
            return
        }

        isPaused = false
        lastAgentApiKey = agentApiKey
        chatJob?.cancel()
        resetProviderTags()
        callbacks.onAgentThinkingStarted(message)

        if (shouldContinueCurrentAgentTask()) {
            EvalRecorder.active?.markUserIntervened("user_message")
            val goal = agentState.currentGoal.ifBlank { latestUserMessage }
            val pending = agentState.pendingQuestion
            agentState = agentState.copy(needsUserInput = false, pendingQuestion = null)
            if (pending != null) {
                conversation.addUser(
                    "The phone agent is mid-task working on: \"$goal\". It asked the user: \"$pending\". The user's exact reply follows. If phone control is needed, call use_phone; the app will pass the exact reply through without using your task wording."
                )
            }
            capturedInitialScreenDump = screenReader()?.takeIf { it.isNotBlank() }
            latestUserMessage = message
            chatJob = scope.launch {
                runCatching { routeMessage(chatApiKey = agentApiKey, agentApiKey = agentApiKey, message = message) }
                    .onFailure {
                        val evalCancelled = it is CancellationException || isPaused
                        if (evalCancelled) EvalRecorder.active?.markUserStopped()
                        EvalRecorder.active?.finalizeIfUnfinished(
                            if (evalCancelled) "cancelled" else "error", latestScreenshotDataUrl
                        )
                        if (evalCancelled) return@onFailure
                        callbacks.onAgentError(it.message)
                    }
                EvalRecorder.active?.finalizeIfUnfinished("chat_only", latestScreenshotDataUrl)
                callbacks.onAgentThinkingFinished()
            }
            return
        }

        // Capture the accessibility tree immediately at prompt submission time so the
        // first agent payload already has screen context, skipping the initial read_screen.
        capturedInitialScreenDump = screenReader()?.takeIf { it.isNotBlank() }

        latestUserMessage = message

        // Chat-first: try a quick conversational response via Haiku.
        // If the model decides it needs to interact with the phone, it calls use_phone.
        chatJob = scope.launch {
            runCatching { routeMessage(chatApiKey = agentApiKey, agentApiKey = agentApiKey, message = message) }
                .onFailure {
                    val evalCancelled = it is CancellationException || isPaused
                    if (evalCancelled) EvalRecorder.active?.markUserStopped()
                    EvalRecorder.active?.finalizeIfUnfinished(
                        if (evalCancelled) "cancelled" else "error", latestScreenshotDataUrl
                    )
                    if (evalCancelled) return@onFailure
                    callbacks.onAgentError(it.message)
                }
            EvalRecorder.active?.finalizeIfUnfinished("chat_only", latestScreenshotDataUrl)
            callbacks.onAgentThinkingFinished()
        }
    }

    fun runPhoneTaskDirectly(message: String) {
        val agentApiKey = agentProviderApiKey()
        if (agentApiKey.isBlank()) {
            callbacks.onAgentApiKeyMissing()
            return
        }

        isPaused = false
        lastAgentApiKey = agentApiKey
        chatJob?.cancel()
        resetProviderTags()
        callbacks.onAgentThinkingStarted(message)

        capturedInitialScreenDump = screenReader()?.takeIf { it.isNotBlank() }
        EvalRecorder.active?.markAgentInvoked()
        resetAgentState(message, mapsWasActive = isMapsInForeground())
        seedInitialScreenDump()
        latestUserMessage = message
        // Eval harness path bypasses chat — keep unified history in sync with the user utterance.
        conversation.addUser(message)
        appendActionTranscriptGoal(message)

        chatJob = scope.launch {
            runCatching { runAgentLoop(agentApiKey) }
                .onFailure {
                    val evalCancelled = it is CancellationException || isPaused
                    if (evalCancelled) EvalRecorder.active?.markUserStopped()
                    EvalRecorder.active?.finalizeIfUnfinished(
                        if (evalCancelled) "cancelled" else "error",
                        latestScreenshotDataUrl
                    )
                    if (evalCancelled) return@onFailure
                    callbacks.onAgentError(it.message)
                }
            callbacks.onAgentThinkingFinished()
        }
    }

    fun pauseAgent() {
        isPaused = true
        chatJob?.cancel()
        chatJob = null
        callbacks.onAgentThinkingFinished()
    }

    fun resumeAgent() {
        if (!isPaused) return
        val apiKey = lastAgentApiKey ?: agentProviderApiKey()
        if (apiKey.isBlank()) {
            callbacks.onAgentApiKeyMissing()
            return
        }

        isPaused = false
        lastAgentApiKey = apiKey
        callbacks.onAgentThinkingStarted("Resuming latest prompt")
        startAgentLoop(apiKey)
    }

    // ─── Chat-first routing ─────────────────────────────────────────────────

    private suspend fun routeMessage(chatApiKey: String, agentApiKey: String, message: String) {
        pendingTurnBlocks.clear()
        conversation.addUser(message)

        var emptyResponseRetries = 0
        for (round in 0 until CHAT_MAX_DIRECT_TOOL_ROUNDS) {
            val result = callChat(chatApiKey)
            val modelCallId = result.optString("_marvin_model_call_id").takeIf { it.isNotBlank() }
            result.optJSONArray("assistant_content")
                ?.takeIf { it.length() > 0 }
                ?.let { pendingTurnBlocks.add(JSONObject().put("role", "assistant").put("content", JSONArray(it.toString()))) }

            val content = result.optString("content", "").takeIf { it != "null" }?.trim() ?: ""
            val toolCalls = result.optJSONArray("tool_calls")

            if (toolCalls != null && toolCalls.length() > 0) {
                emptyResponseRetries = 0
                when (val outcome = handleChatToolLoopTurn(toolCalls, fallbackTask = message)) {
                    is ChatLoopOutcome.Continue -> {
                        updateModelCallTools(modelCallId, outcome.executedTools)
                        pendingTurnBlocks.add(JSONObject().put("role", "user").put("content", outcome.toolResults))
                    }

                    is ChatLoopOutcome.FinalResponse -> {
                        updateModelCallTools(modelCallId, outcome.executedTools)
                        outcome.executedTools.forEach { conversation.addToolCall(it.name, it.argsDisplay, it.resultDisplay) }
                        conversation.addAssistant(outcome.response)
                        pendingTurnBlocks.clear()
                        if (outcome.isAskUser) {
                            callbacks.onAgentAskUser(outcome.response, outcome.autoListen)
                        } else {
                            callbacks.onAgentResponse(outcome.response)
                        }
                        return
                    }

                    is ChatLoopOutcome.HandoffToPhone -> {
                        updateModelCallTools(modelCallId, outcome.executedTools)
                        outcome.executedTools.forEach { conversation.addToolCall(it.name, it.argsDisplay, it.resultDisplay) }
                        val task = outcome.task.ifBlank { message }
                        conversation.addAssistant("Let me do that on your phone.")
                        pendingTurnBlocks.clear()
                        EvalRecorder.active?.markAgentInvoked()
                        resetAgentState(task, mapsWasActive = isMapsInForeground())
                        seedInitialScreenDump()
                        // Goal is the Haiku-resolved task; keep latestUserMessage as the raw user input.
                        latestUserMessage = message
                        appendActionTranscriptGoal(task)
                        runAgentLoop(agentApiKey)
                        return
                    }
                }
            } else if (content.isNotBlank()) {
                emptyResponseRetries = 0
                val responseText = sanitizeForTts(content)
                conversation.addAssistant(responseText)
                pendingTurnBlocks.clear()
                callbacks.onAgentResponse(responseText)
                return
            } else {
                Log.w("AgentManager", "Chat returned empty response (round=$round): $result")
                if (emptyResponseRetries >= CHAT_EMPTY_RESPONSE_RETRY_CAP) break
                emptyResponseRetries++
                queueChatSystemNudge(
                    "System note: your last response was empty — no text and no tool call. " +
                        "Please reply to the user, or call a tool if you need information. Continue now."
                )
            }
        }

        // Finalize fallback: force one plain-text answer.
        queueChatSystemNudge(
            "System note: please respond to the user now in plain text. Do not call any more tools."
        )
        val finalize = runCatching { callChat(chatApiKey) }.getOrNull()
        val finalText = finalize?.optString("content", "")
            ?.takeIf { it != "null" }?.trim()
            .orEmpty()
        pendingTurnBlocks.clear()
        if (finalText.isNotBlank()) {
            val responseText = sanitizeForTts(finalText)
            conversation.addAssistant(responseText)
            callbacks.onAgentResponse(responseText)
        } else {
            callbacks.onAgentResponse("Sorry — I didn't catch a response. Try asking again.")
        }
    }

    // Append a user-role nudge to pendingTurnBlocks. Anthropic requires strict
    // role alternation, so insert a placeholder assistant block first when the
    // last queued/historical message is also user (e.g. the model just returned
    // an empty turn, so no assistant block was appended).
    private fun queueChatSystemNudge(text: String) {
        val lastPendingRole = pendingTurnBlocks.lastOrNull()?.optString("role")
        val needsAssistantBridge = lastPendingRole == null || lastPendingRole == "user"
        if (needsAssistantBridge) {
            pendingTurnBlocks.add(
                JSONObject().put("role", "assistant").put("content", "(no response)")
            )
        }
        pendingTurnBlocks.add(
            JSONObject().put("role", "user").put("content", text)
        )
    }

    private suspend fun handleChatToolLoopTurn(
        toolCalls: JSONArray,
        fallbackTask: String
    ): ChatLoopOutcome {
        val toolResults = JSONArray()
        val executed = mutableListOf<ExecutedChatTool>()

        fun record(toolName: String, rawArgs: String, resultContent: JSONObject) {
            executed.add(
                ExecutedChatTool(
                    name = toolName,
                    rawArgs = rawArgs,
                    argsDisplay = ConversationHistory.formatToolArgsForDisplay(toolName, rawArgs),
                    resultDisplay = ConversationHistory.formatToolResultForHistory(toolName, resultContent.toString()),
                    success = resultContent.optBoolean("ok", false)
                )
            )
        }

        for (index in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(index) ?: continue
            val toolUseId = toolCall.optString("id").ifBlank { "chat_tool_$index" }
            val function = toolCall.optJSONObject("function") ?: continue
            val toolName = function.optString("name").trim()
            val rawArgs = function.optString("arguments", "{}")
            val arguments = runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())

            if (toolName == ChatPrompt.TOOL_OPEN_APP) {
                val result = executeChatOpenApp(arguments)
                result.uiAction?.let(callbacks::onAgentUiAction)
                toolResults.put(
                    ChatToolLoopSupport.toolResultBlock(
                        toolUseId = toolUseId,
                        resultContent = result.resultContent,
                        isError = !result.resultContent.optBoolean("ok", false)
                    )
                )
                record(toolName, rawArgs, result.resultContent)
                if (!result.resultContent.optBoolean("ok", false)) {
                    ChatToolLoopSupport.appendSkippedToolResults(
                        toolCalls = toolCalls,
                        startIndex = index + 1,
                        toolResults = toolResults,
                        reason = "Skipped because an earlier tool call in this response already failed."
                    )
                    return ChatLoopOutcome.Continue(toolResults, executed)
                }
                continue
            }

            if (toolName == ChatPrompt.TOOL_OPEN_NOTIFICATIONS) {
                val result = executeChatOpenNotifications()
                result.uiAction?.let(callbacks::onAgentUiAction)
                toolResults.put(
                    ChatToolLoopSupport.toolResultBlock(
                        toolUseId = toolUseId,
                        resultContent = result.resultContent,
                        isError = !result.resultContent.optBoolean("ok", false)
                    )
                )
                record(toolName, rawArgs, result.resultContent)
                if (!result.resultContent.optBoolean("ok", false)) {
                    ChatToolLoopSupport.appendSkippedToolResults(
                        toolCalls = toolCalls,
                        startIndex = index + 1,
                        toolResults = toolResults,
                        reason = "Skipped because an earlier tool call in this response already failed."
                    )
                    return ChatLoopOutcome.Continue(toolResults, executed)
                }
                continue
            }

            if (toolName == ChatPrompt.TOOL_READ_SCREEN) {
                callbacks.onToolCalled(toolName, rawArgs)
                val result = toolExecutor.executeToolCall(toolCall)
                val content = JSONObject(result.toolMessage.optString("content", "{}"))
                recordToolOutcome(toolName = toolName, rawArgs = rawArgs, content = content)
                val chatContent = JSONObject(content.toString())
                val imageDataUrl = chatContent.optString("image_data_url").takeIf { it.isNotBlank() }
                if (imageDataUrl != null) {
                    chatContent.remove("image_data_url")
                    chatContent.put("screenshot_captured", true)
                }
                toolResults.put(
                    chatReadScreenToolResultBlock(
                        toolUseId = toolUseId,
                        resultContent = chatContent,
                        imageDataUrl = imageDataUrl,
                        isError = !content.optBoolean("ok", false)
                    )
                )
                record(toolName, rawArgs, chatContent)
                if (!content.optBoolean("ok", false)) {
                    ChatToolLoopSupport.appendSkippedToolResults(
                        toolCalls = toolCalls,
                        startIndex = index + 1,
                        toolResults = toolResults,
                        reason = "Skipped because an earlier tool call in this response already failed."
                    )
                    return ChatLoopOutcome.Continue(toolResults, executed)
                }
                continue
            }

            if (toolName == ChatPrompt.TOOL_ASK_USER) {
                callbacks.onToolCalled(toolName, rawArgs)
                val question = arguments.optString("question").trim()
                val normalized = AgentToolExecutorSupport.normalizeQuickQuestion(question)
                val autoListen = arguments.optBoolean("auto_listen", true)
                val resultContent = JSONObject()
                    .put("tool", ChatPrompt.TOOL_ASK_USER)
                    .put("ok", true)
                    .put("question", normalized)
                toolResults.put(
                    ChatToolLoopSupport.toolResultBlock(
                        toolUseId = toolUseId,
                        resultContent = resultContent,
                        isError = false
                    )
                )
                record(toolName, rawArgs, resultContent)
                ChatToolLoopSupport.appendSkippedToolResults(
                    toolCalls = toolCalls,
                    startIndex = index + 1,
                    toolResults = toolResults,
                    reason = "Skipped because ask_user already needs a reply."
                )
                return ChatLoopOutcome.FinalResponse(
                    response = sanitizeForTts(normalized),
                    isAskUser = true,
                    autoListen = autoListen,
                    toolResults = toolResults,
                    executedTools = executed
                )
            }

            if (toolName == ChatPrompt.TOOL_USE_PHONE) {
                callbacks.onToolCalled(toolName, rawArgs)
                val resolvedGoal = arguments.optString("goal").trim().ifBlank { fallbackTask }
                val resultContent = JSONObject()
                    .put("tool", ChatPrompt.TOOL_USE_PHONE)
                    .put("ok", true)
                    .put("goal", resolvedGoal)
                    .put("handoff", true)
                toolResults.put(
                    ChatToolLoopSupport.toolResultBlock(
                        toolUseId = toolUseId,
                        resultContent = resultContent,
                        isError = false
                    )
                )
                record(toolName, rawArgs, resultContent)
                ChatToolLoopSupport.appendSkippedToolResults(
                    toolCalls = toolCalls,
                    startIndex = index + 1,
                    toolResults = toolResults,
                    reason = "Skipped because use_phone is handling the request."
                )
                return ChatLoopOutcome.HandoffToPhone(resolvedGoal, toolResults, executed)
            }

            if (toolName in DIRECT_CHAT_SHARED_TOOLS) {
                callbacks.onToolCalled(toolName, rawArgs)
                val execution = sharedToolExecutor.execute(toolName, arguments)
                if (execution == null) {
                    val errorContent = ChatToolLoopSupport.errorResultContent(
                        toolName = toolName,
                        error = "Unknown shared tool: $toolName"
                    )
                    toolResults.put(
                        ChatToolLoopSupport.toolResultBlock(
                            toolUseId = toolUseId,
                            resultContent = errorContent,
                            isError = true
                        )
                    )
                    record(toolName, rawArgs, errorContent)
                    ChatToolLoopSupport.appendSkippedToolResults(
                        toolCalls = toolCalls,
                        startIndex = index + 1,
                        toolResults = toolResults,
                        reason = "Skipped because an earlier tool call in this response already failed."
                    )
                    return ChatLoopOutcome.Continue(toolResults, executed)
                }
                execution.uiAction?.let(callbacks::onAgentUiAction)
                toolResults.put(
                    ChatToolLoopSupport.toolResultBlock(
                        toolUseId = toolUseId,
                        resultContent = execution.content,
                        isError = !execution.content.optBoolean("ok", false)
                    )
                )
                record(toolName, rawArgs, execution.content)
                if (!execution.content.optBoolean("ok", false)) {
                    ChatToolLoopSupport.appendSkippedToolResults(
                        toolCalls = toolCalls,
                        startIndex = index + 1,
                        toolResults = toolResults,
                        reason = "Skipped because an earlier tool call in this response already failed."
                    )
                    return ChatLoopOutcome.Continue(toolResults, executed)
                }
                continue
            }

            val errorContent = ChatToolLoopSupport.errorResultContent(
                toolName = toolName.ifBlank { "unknown_tool" },
                error = "Unsupported chat tool: ${toolName.ifBlank { "unknown_tool" }}"
            )
            toolResults.put(
                ChatToolLoopSupport.toolResultBlock(
                    toolUseId = toolUseId,
                    resultContent = errorContent,
                    isError = true
                )
            )
            record(toolName.ifBlank { "unknown_tool" }, rawArgs, errorContent)
            ChatToolLoopSupport.appendSkippedToolResults(
                toolCalls = toolCalls,
                startIndex = index + 1,
                toolResults = toolResults,
                reason = "Skipped because an earlier tool call in this response already failed."
            )
            return ChatLoopOutcome.Continue(toolResults, executed)
        }

        return if (toolResults.length() > 0) {
            ChatLoopOutcome.Continue(toolResults, executed)
        } else {
            ChatLoopOutcome.FinalResponse("I couldn't complete that request.", executedTools = executed)
        }
    }

    private fun chatReadScreenToolResultBlock(
        toolUseId: String,
        resultContent: JSONObject,
        imageDataUrl: String?,
        isError: Boolean
    ): JSONObject {
        val block = JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", toolUseId)
            .put("is_error", isError)

        val imageBlock = imageDataUrl?.let(::anthropicImageBlock)
        if (imageBlock == null) {
            block.put("content", resultContent.toString())
        } else {
            block.put(
                "content",
                JSONArray()
                    .put(anthropicTextBlock(resultContent.toString()))
                    .put(imageBlock)
            )
        }
        return block
    }

    private fun executeChatOpenApp(arguments: JSONObject): SharedChatToolResult {
        callbacks.onToolCalled(ChatPrompt.TOOL_OPEN_APP, arguments.toString())
        val appName = arguments.optString("name").trim().lowercase()
        if (appName.isBlank()) {
            return SharedChatToolResult(
                toolName = ChatPrompt.TOOL_OPEN_APP,
                response = "I need the app name first.",
                resultContent = JSONObject()
                    .put("tool", ChatPrompt.TOOL_OPEN_APP)
                    .put("ok", false)
                    .put("error", "Missing app name.")
            )
        }

        val result = openAppForChat(appName)
        val response = when {
            result.opened && result.alreadyOpen -> "${result.label ?: appName} is already open."
            result.opened -> "Opened ${result.label ?: appName}."
            else -> result.error ?: "I could not open $appName."
        }
        return SharedChatToolResult(
            toolName = ChatPrompt.TOOL_OPEN_APP,
            response = sanitizeForTts(response),
            resultContent = JSONObject()
                .put("tool", ChatPrompt.TOOL_OPEN_APP)
                .put("ok", result.opened)
                .put("opened", result.opened)
                .put("already_open", result.alreadyOpen)
                .put("app_name", appName)
                .apply {
                    result.packageName?.let { put("package_name", it) }
                    result.label?.let { put("resolved_label", it) }
                    result.error?.let { put("error", it) }
                }
        )
    }

    private fun executeChatOpenNotifications(): SharedChatToolResult {
        callbacks.onToolCalled(ChatPrompt.TOOL_OPEN_NOTIFICATIONS)
        val result = openNotificationsForChat()
        val response = when {
            result.pressed -> "Opened notifications."
            else -> result.error ?: "I could not open notifications."
        }
        return SharedChatToolResult(
            toolName = ChatPrompt.TOOL_OPEN_NOTIFICATIONS,
            response = sanitizeForTts(response),
            resultContent = JSONObject()
                .put("tool", ChatPrompt.TOOL_OPEN_NOTIFICATIONS)
                .put("ok", result.pressed)
                .put("opened", result.pressed)
                .apply {
                    result.error?.let { put("error", it) }
                }
        )
    }

    private fun openAppForChat(appName: String): AppOpenResult {
        return AssistantAccessibilityService.instance?.let { service ->
            AppOpener.openApp(service, appName)
        } ?: AppOpenResult(
            opened = false,
            error = "Accessibility service is not connected."
        )
    }

    private fun openNotificationsForChat(): PressHomeResult {
        return AssistantAccessibilityService.instance?.let { service ->
            val pressed = service.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            )
            PressHomeResult(pressed = pressed)
        } ?: PressHomeResult(
            pressed = false,
            error = "Accessibility service is not connected."
        )
    }

    private fun resetAgentState(currentGoal: String, mapsWasActive: Boolean = false) {
        latestScreenDump = null
        latestScreenRevision = null
        latestScreenshotDataUrl = null
        latestScreenshotRevision = null
        agentState = emptyAgentState(currentGoal = currentGoal).copy(mapsWasActive = mapsWasActive)
        toolCallLog.clear()
        evalTranscript.clear()
        lastUiChange = "new_user_request"
        lastSuccessfulToolName = null
        lastTappedNodeRef = null
        lastTappedLabel = null
        consecutiveSameToolCount = 0
        lastToolCallSignature = null
    }

    private fun seedInitialScreenDump() {
        val dump = capturedInitialScreenDump?.takeIf { it.isNotBlank() } ?: return
        capturedInitialScreenDump = null
        latestScreenDump = dump
        agentState = agentState.copy(lastTree = ScreenReader.fingerprintsForDump(dump))
    }

    private fun isMapsInForeground(): Boolean {
        val service = AssistantAccessibilityService.instance ?: return false
        val root = service.getUnderlyingAppRoot() ?: return false
        return try {
            root.packageName?.toString()?.trim() == "com.google.android.apps.maps"
        } finally {
            root.recycle()
        }
    }

    private fun returnToMaps() {
        val intent = appContext.packageManager
            .getLaunchIntentForPackage("com.google.android.apps.maps") ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        appContext.startActivity(intent)
    }

    private fun agentProviderApiKey(): String {
        return BuildConfig.OPENROUTER_API_KEY.trim()
            .ifBlank { BuildConfig.ANTHROPIC_API_KEY.trim() }
            .ifBlank { BuildConfig.OPENAI_API_KEY.trim() }
    }

    private fun resetProviderTags() {
        synchronized(displayedProviderTags) {
            displayedProviderTags.clear()
        }
    }

    private fun emitProviderTag(providerLabel: String) {
        val shouldShow = synchronized(displayedProviderTags) {
            displayedProviderTags.add(providerLabel)
        }
        if (shouldShow) {
            scope.launch(Dispatchers.Main) {
                callbacks.onAgentProviderTag(providerLabel)
            }
        }
    }

    private suspend fun callChat(apiKey: String): JSONObject {
        val provider = appContext.getSharedPreferences("marvin_prefs", android.content.Context.MODE_PRIVATE)
            .getString("pref_ai_provider", "anthropic")
        return if (provider == "openai") {
            callOpenAiChatDirect()
        } else {
            try {
                callQwenChat()
            } catch (qwenError: Exception) {
                AgentModelConfig.deepSeekChatFallbackFor(ChatPrompt.MODEL)?.let { fallbackModel ->
                    return try {
                        callOpenRouterChat(fallbackModel, "DeepSeek")
                    } catch (fallbackError: Exception) {
                        fallbackError.addSuppressed(qwenError)
                        throw fallbackError
                    }
                }
                if (!AgentModelConfig.allowAnthropicChatFallbackFor(ChatPrompt.MODEL)) {
                    throw qwenError
                }
                try {
                    callAnthropicChat(AgentModelConfig.ANTHROPIC_CHAT_BACKUP_MODEL)
                } catch (anthropicError: Exception) {
                    anthropicError.addSuppressed(qwenError)
                    callOpenAiChatFallback(anthropicError, AgentModelConfig.ANTHROPIC_CHAT_BACKUP_MODEL)
                }
            }
        }
    }

    private suspend fun callQwenChat(): JSONObject {
        return callOpenRouterChat(ChatPrompt.MODEL, "DeepSeek")
    }

    private suspend fun callOpenRouterChat(
        model: String,
        providerTag: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val openRouterApiKey = BuildConfig.OPENROUTER_API_KEY.trim()
        if (openRouterApiKey.isBlank()) throw IllegalStateException("OpenRouter API key not configured")

        activeSearchContext = SearchExecutionContext(
            provider = SearchProvider.OPENAI,
            model = AgentModelConfig.OPENAI_WEB_SEARCH_MODEL
        )
        val requestPayload = JSONObject()
            .put("model", model)
            .put("max_tokens", CHAT_MAX_TOKENS)
            .put("messages", buildOpenAiChatMessages())
            .put("tools", buildOpenAiChatTools())
            .put("tool_choice", "auto")

        val body = requestPayload
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(OPENROUTER_CHAT_COMPLETIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $openRouterApiKey")
            .post(body)
            .build()

        val startedAtMs = System.currentTimeMillis()
        chatClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                throw IllegalStateException(
                    AgentApiSupport.formatAgentApiError(
                        providerLabel = "openrouter",
                        model = model,
                        statusCode = response.code,
                        responseBody = responseBody,
                        maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                    )
                )
            }
            val responseJson = JSONObject(response.body?.string().orEmpty())
            val usageRecord = recordModelUsage(
                "openrouter",
                model,
                responseJson,
                durationMs = System.currentTimeMillis() - startedAtMs
            )
            emitProviderTag(providerTag)
            attachModelCallId(AgentApiSupport.parseOpenAiChatCompletionResult(responseJson), usageRecord)
        }
    }

    private suspend fun callOpenAiChatDirect(): JSONObject = withContext(Dispatchers.IO) {
        val openAiApiKey = BuildConfig.OPENAI_API_KEY.trim()
        if (openAiApiKey.isBlank()) throw IllegalStateException("OpenAI API key not configured")

        val fallbackModel = AgentModelConfig.openAiFallbackModelFor(ChatPrompt.MODEL)
        activeSearchContext = SearchExecutionContext(
            provider = SearchProvider.OPENAI,
            model = fallbackModel
        )
        val requestPayload = JSONObject()
            .put("model", fallbackModel)
            .put("max_completion_tokens", CHAT_MAX_TOKENS)
            .put("messages", buildOpenAiChatMessages())
            .put("tools", buildOpenAiChatTools())
            .put("tool_choice", "auto")

        val body = requestPayload
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(OPENAI_CHAT_COMPLETIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .post(body)
            .build()

        val startedAtMs = System.currentTimeMillis()
        chatClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                throw IllegalStateException(
                    AgentApiSupport.formatAgentApiError(
                        providerLabel = "openai",
                        model = fallbackModel,
                        statusCode = response.code,
                        responseBody = responseBody,
                        maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                    )
                )
            }
            val responseJson = JSONObject(response.body?.string().orEmpty())
            val usageRecord = recordModelUsage(
                "openai",
                fallbackModel,
                responseJson,
                durationMs = System.currentTimeMillis() - startedAtMs
            )
            emitProviderTag("ChatGPT")
            attachModelCallId(AgentApiSupport.parseOpenAiChatCompletionResult(responseJson), usageRecord)
        }
    }

    private suspend fun callAnthropicChat(model: String): JSONObject = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY.trim()
        if (apiKey.isBlank()) throw IllegalStateException("Anthropic API key not configured")

        activeSearchContext = SearchExecutionContext(
            provider = SearchProvider.ANTHROPIC,
            model = model
        )
        val requestPayload = JSONObject()
            .put("model", model)
            .put("max_tokens", CHAT_MAX_TOKENS)
            .put("system", ChatPrompt.instructions(memoryPromptSnapshot()))
            .put("messages", buildAnthropicChatMessages())
            .put("tools", ChatPrompt.buildAnthropicTools())

        val body = requestPayload
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(ANTHROPIC_MESSAGES_ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(body)
            .build()

        val startedAtMs = System.currentTimeMillis()
        chatClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                throw IllegalStateException(
                    AgentApiSupport.formatAgentApiError(
                        providerLabel = "anthropic",
                        model = model,
                        statusCode = response.code,
                        responseBody = responseBody,
                        maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                    )
                )
            }
            val responseJson = JSONObject(response.body?.string().orEmpty())
            val usageRecord = recordModelUsage(
                "anthropic",
                model,
                responseJson,
                durationMs = System.currentTimeMillis() - startedAtMs
            )
            emitProviderTag("Anthropic")
            attachModelCallId(AgentApiSupport.parseAnthropicChatResult(responseJson), usageRecord)
        }
    }

    private suspend fun callOpenAiChatFallback(
        anthropicError: Exception,
        sourceModel: String = ChatPrompt.MODEL
    ): JSONObject = withContext(Dispatchers.IO) {
        val openAiApiKey = BuildConfig.OPENAI_API_KEY.trim()
        if (openAiApiKey.isBlank()) throw anthropicError

        val fallbackModel = AgentModelConfig.openAiFallbackModelFor(sourceModel)
        activeSearchContext = SearchExecutionContext(
            provider = SearchProvider.OPENAI,
            model = fallbackModel
        )
        val requestPayload = JSONObject()
            .put("model", fallbackModel)
            .put("max_completion_tokens", CHAT_MAX_TOKENS)
            .put("messages", buildOpenAiChatMessages())
            .put("tools", buildOpenAiChatTools())
            .put("tool_choice", "auto")

        val body = requestPayload
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(OPENAI_CHAT_COMPLETIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .post(body)
            .build()

        val startedAtMs = System.currentTimeMillis()
        chatClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                val fallbackError = IllegalStateException(
                    AgentApiSupport.formatAgentApiError(
                        providerLabel = "openai",
                        model = fallbackModel,
                        statusCode = response.code,
                        responseBody = responseBody,
                        maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                    )
                )
                fallbackError.addSuppressed(anthropicError)
                throw fallbackError
            }
            val responseJson = JSONObject(response.body?.string().orEmpty())
            val usageRecord = recordModelUsage(
                "openai",
                fallbackModel,
                responseJson,
                callType = "fallback",
                durationMs = System.currentTimeMillis() - startedAtMs
            )
            emitProviderTag("ChatGPT")
            attachModelCallId(AgentApiSupport.parseOpenAiChatCompletionResult(responseJson), usageRecord)
        }
    }

    /**
     * Anthropic requires the first message to be from the user and roles to
     * strictly alternate, so coalesce consecutive same-role history entries
     * and drop a leading assistant turn.
     */
    private fun buildAnthropicChatMessages(): JSONArray {
        val messages = conversation.toAnthropicChatMessages()
        for (block in pendingTurnBlocks) {
            messages.put(block)
        }
        return messages
    }

    private fun buildOpenAiChatMessages(): JSONArray {
        val history = conversation.toAnthropicChatMessages().toJsonObjectList() + pendingTurnBlocks
        return AgentApiSupport.buildOpenAiChatMessages(
            systemPrompt = ChatPrompt.instructions(memoryPromptSnapshot()),
            chatHistory = history
        )
    }

    private fun buildOpenAiChatTools(): JSONArray {
        val tools = ChatPrompt.buildTools()
        return JSONArray().also { out ->
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                if (tool.optString("type") != "function") continue
                out.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.optString("name"))
                                .put("description", tool.optString("description"))
                                .put("parameters", tool.optJSONObject("parameters") ?: JSONObject())
                        )
                )
            }
        }
    }

    private fun JSONArray.toJsonObjectList(): List<JSONObject> {
        return buildList {
            for (i in 0 until length()) {
                optJSONObject(i)?.let(::add)
            }
        }
    }

    private fun memoryPromptSnapshot(): MemoryPromptSnapshot {
        return runCatching { memoryRepository.promptSnapshot() }
            .getOrDefault(MemoryPromptSnapshot.EMPTY)
    }

    private fun performWebSearch(query: String): SearchWebResult {
        val context = activeSearchContext
        return when (context.provider) {
            SearchProvider.ANTHROPIC -> performAnthropicWebSearch(query, context.model)
            SearchProvider.OPENAI -> performOpenAiWebSearch(query, context.model)
        }
    }

    private fun performAnthropicWebSearch(query: String, model: String): SearchWebResult {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY.trim()
        if (apiKey.isBlank()) {
            return SearchWebResult(ok = false, error = "API key not available.")
        }

        return try {
            val requestPayload = JSONObject()
                .put("model", model)
                .put("max_tokens", WEB_SEARCH_MAX_TOKENS)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", query)
                    )
                )
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "web_search_20250305")
                            .put("name", "web_search")
                            .put("max_uses", WEB_SEARCH_MAX_USES)
                    )
                )

            val body = requestPayload.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(ANTHROPIC_MESSAGES_ENDPOINT)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .post(body)
                .build()

            val startedAtMs = System.currentTimeMillis()
            chatClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    SearchWebResult(ok = false, error = "HTTP ${response.code}: $responseBody")
                } else {
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    recordModelUsageAsync(
                        "anthropic",
                        model,
                        responseJson,
                        callType = "search",
                        durationMs = System.currentTimeMillis() - startedAtMs
                    )
                    val content = responseJson.optJSONArray("content") ?: JSONArray()
                    val textParts = StringBuilder()
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") {
                            textParts.append(part.optString("text"))
                        }
                    }
                    val answer = AgentApiSupport.stripCitationMarkers(textParts.toString())
                    SearchWebResult(ok = true, answer = answer.ifBlank { null })
                }
            }
        } catch (e: Exception) {
            SearchWebResult(ok = false, error = e.message ?: "Search failed.")
        }
    }

    private fun performOpenAiWebSearch(query: String, model: String): SearchWebResult {
        val apiKey = BuildConfig.OPENAI_API_KEY.trim()
        if (apiKey.isBlank()) {
            return SearchWebResult(ok = false, error = "API key not available.")
        }

        val searchModel = AgentModelConfig.openAiWebSearchModelFor(model)

        return try {
            val requestPayload = JSONObject()
                .put("model", searchModel)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", query)
                    )
                )
                .put("web_search_options", JSONObject())

            val body = requestPayload.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(OPENAI_CHAT_COMPLETIONS_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val startedAtMs = System.currentTimeMillis()
            chatClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    SearchWebResult(ok = false, error = "HTTP ${response.code}: $responseBody")
                } else {
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    recordModelUsageAsync(
                        "openai",
                        searchModel,
                        responseJson,
                        callType = "search",
                        durationMs = System.currentTimeMillis() - startedAtMs,
                        webSearchRequestsOverride = 1
                    )
                    val answer = AgentApiSupport.parseOpenAiChatCompletionText(responseJson)
                        .optString("content")
                        .trim()
                    SearchWebResult(
                        ok = true,
                        answer = AgentApiSupport.stripCitationMarkers(answer).ifBlank { null }
                    )
                }
            }
        } catch (e: Exception) {
            SearchWebResult(ok = false, error = e.message ?: "Search failed.")
        }
    }

    // ─── Agent loop ─────────────────────────────────────────────────────────

    private suspend fun runAgentLoop(apiKey: String) {
        val currentGoal = agentState.currentGoal.ifBlank { latestUserMessage }
        agentState = agentState.copy(currentGoal = currentGoal)
        var hasUserFacingOutput = false

        if (evalTranscript.isEmpty() && currentGoal.isNotBlank()) {
            appendActionTranscriptGoal(currentGoal)
        }

        var evalStopReason = "max_steps"
        var emptyActionRetries = 0

        for (step in 0 until AgentTooling.MAX_AGENT_STEPS) {
            EvalRecorder.active?.beginStep(step)
            val assistantMessage = callAgent()
            val modelCallId = assistantMessage.optString("_marvin_model_call_id").takeIf { it.isNotBlank() }
            val toolTelemetry = mutableListOf<ToolCallTelemetry>()

            val rawContent = assistantMessage.optString("content", "")
                .takeIf { it != "null" }?.trim() ?: ""
            val structuredResponse = AgentSessionFormatting.parseStructuredAgentResponse(rawContent)

            structuredResponse.mission?.let { mission ->
                updateMissionState(mission)
            }

            // Update next steps
            if (structuredResponse.nextSteps.isNotEmpty()) {
                agentState = agentState.copy(nextSteps = structuredResponse.nextSteps)
            }

            // Surface reasoning to the user (text bubble in the chat card, not TTS) and
            // persist it into the unified conversation so the agent can recall its own
            // prior-step thinking on the next turn.
            val thinking = structuredResponse.thinking
            if (thinking.isNotBlank()) {
                EvalRecorder.active?.recordStepThinking(thinking)
                callbacks.onAgentReasoning(thinking)
                conversation.addReasoning(thinking)
            }

            // Every response MUST end in a tool call. If the model returned no actions,
            // inject a corrective system note and retry. Cap retries so we cannot loop.
            val actions = structuredResponse.actions
            if (actions.isEmpty()) {
                if (emptyActionRetries >= EMPTY_ACTION_RETRY_CAP) {
                    evalStopReason = "empty_actions"
                    break
                }
                emptyActionRetries++
                conversation.addUser(
                    "System note: your last response had no actions. Every response MUST include at least one action. To finish call task_complete; to ask the user call ask_user. Continue now with at least one action."
                )
                continue
            }
            emptyActionRetries = 0

            var shouldStopLoop = false
            for ((actionIdx, action) in actions.withIndex()) {
                if (action.optString("tool").isBlank()) continue

                val canonName = AgentTooling.canonicalToolName(action.optString("tool"))
                val actionArgs = JSONObject(action.toString()).apply { remove("tool") }
                val rawArgs = actionArgs.toString()

                val toolCall = JSONObject()
                    .put("id", "action_${step}_${actionIdx}")
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", canonName)
                        .put("arguments", rawArgs)
                    )

                callbacks.onToolCalled(canonName, rawArgs)
                val result = toolExecutor.executeToolCall(toolCall)
                val resultContent = JSONObject(result.toolMessage.optString("content", "{}"))
                recordToolOutcome(toolName = canonName, rawArgs = rawArgs, content = resultContent)
                toolTelemetry.add(
                    ToolCallTelemetry(
                        name = canonName,
                        rawArgs = rawArgs,
                        success = resultContent.optBoolean("ok", false),
                        resultSummary = AgentSessionFormatting
                            .buildToolOutcomeDetails(canonName, rawArgs, resultContent)
                            .take(400)
                    )
                )
                EvalRecorder.active?.let { rec ->
                    val last = toolCallLog.lastOrNull()
                    if (last != null) {
                        rec.recordToolCall(step, last.name, last.status, rawArgs, last.details)
                        if (last.status != "success") {
                            rec.captureScreenshot(step, "error", latestScreenshotDataUrl)
                        }
                    }
                }
                appendActionTranscriptToolResult(canonName, rawArgs, resultContent)
                conversation.addToolCall(
                    canonName,
                    ConversationHistory.formatToolArgsForDisplay(canonName, rawArgs),
                    ConversationHistory.formatToolResultForHistory(canonName, resultContent.toString())
                )
                hasUserFacingOutput = hasUserFacingOutput || result.hasUserFacingOutput

                agentState = when (canonName) {
                    AgentTooling.TOOL_ASK_USER -> agentState.copy(
                        needsUserInput = true,
                        pendingQuestion = resultContent.optString("question").ifBlank { null }
                    )
                    AgentTooling.TOOL_TASK_COMPLETE -> {
                        val completedPlan = markPlanCompleted(agentState.plan)
                        agentState.copy(
                            plan = completedPlan,
                            completedSteps = completedPlan.filter { it.status == StepStatus.DONE }.map { it.id },
                            needsUserInput = false,
                            pendingQuestion = null
                        )
                    }
                    else -> agentState
                }

                if (result.shouldStopLoop) {
                    evalStopReason = when (canonName) {
                        AgentTooling.TOOL_TASK_COMPLETE -> "task_complete"
                        AgentTooling.TOOL_ASK_USER -> {
                            EvalRecorder.active?.markUserIntervened("ask_user")
                            "ask_user"
                        }
                        else -> "tool_stop"
                    }
                    shouldStopLoop = true
                    break
                }

                // Hard loop breaker: if the same tool+args repeated too many times, force stop
                if (isHardLoopDetected()) {
                    callbacks.onAgentCutoff(
                        "hard_loop",
                        "I got stuck in a loop and stopped. Try rephrasing your request or doing part of the task manually first."
                    )
                    hasUserFacingOutput = true
                    evalStopReason = "hard_loop"
                    shouldStopLoop = true
                    break
                }

                // Stop executing remaining batched actions when the tool failed (model needs
                // to see the result and replan) or read_screen was called (model explicitly
                // wants fresh state before the next actions).
                val toolOk = resultContent.optBoolean("ok", false)
                if (!toolOk || canonName == AgentTooling.TOOL_READ_SCREEN) {
                    break
                }
            }

            updateModelCallToolTelemetry(modelCallId, toolTelemetry)

            if (shouldStopLoop) break

            if (step == AgentTooling.MAX_AGENT_STEPS - 1) {
                val question = "quick question, I've used all my steps — shall I keep going?"
                callbacks.onAgentAskUser(question)
                appendActionTranscriptAssistantMessage(question)
                conversation.addAssistant(question)
                EvalRecorder.active?.markUserIntervened("ask_user")
                agentState = agentState.copy(
                    needsUserInput = true,
                    pendingQuestion = question
                )
                hasUserFacingOutput = true
            }

        }

        EvalRecorder.active?.finalize(
            stopReason = evalStopReason,
            taskCompleteFired = (evalStopReason == "task_complete"),
            hardLoopDetected = isHardLoopDetected(),
            maxConsecutiveSameToolCount = consecutiveSameToolCount,
            transcript = evalTranscript.toList(),
            finalScreenshotDataUrl = latestScreenshotDataUrl
        )

        if (evalStopReason == "task_complete" && agentState.mapsWasActive) {
            returnToMaps()
        }

        if (!hasUserFacingOutput) {
            callbacks.onAgentCutoff(
                evalStopReason,
                "I stopped early before finishing. Say \"try again\" to continue."
            )
        }
    }

    private fun startAgentLoop(apiKey: String) {
        chatJob = scope.launch {
            runCatching { runAgentLoop(apiKey) }
                .onFailure {
                    val evalCancelled = it is CancellationException || isPaused
                    if (evalCancelled) EvalRecorder.active?.markUserStopped()
                    EvalRecorder.active?.finalizeIfUnfinished(
                        if (evalCancelled) "cancelled" else "error", latestScreenshotDataUrl
                    )
                    if (evalCancelled) return@onFailure
                    callbacks.onAgentError(
                        it.message ?: "Agent failed (anthropic/${AgentModelConfig.AGENT_MODEL})"
                    )
                }
            callbacks.onAgentThinkingFinished()
        }
    }

    private suspend fun callAgent(): JSONObject {
        val requestedModel = AgentModelConfig.AGENT_MODEL
        if (requestedModel.contains("claude", ignoreCase = true)) {
            return try {
                callAnthropicAgent(requestedModel)
            } catch (anthropicError: Exception) {
                callOpenAiAgentFallback(anthropicError, requestedModel)
            }
        }

        return try {
            callQwenAgent()
        } catch (qwenError: Exception) {
            try {
                callAnthropicAgent(AgentModelConfig.ANTHROPIC_AGENT_BACKUP_MODEL)
            } catch (anthropicError: Exception) {
                anthropicError.addSuppressed(qwenError)
                callOpenAiAgentFallback(anthropicError, AgentModelConfig.ANTHROPIC_AGENT_BACKUP_MODEL)
            }
        }
    }

    private suspend fun callQwenAgent(): JSONObject = withContext(Dispatchers.IO) {
        val openRouterApiKey = BuildConfig.OPENROUTER_API_KEY.trim()
        if (openRouterApiKey.isBlank()) throw IllegalStateException("OpenRouter API key not configured")

        activeSearchContext = SearchExecutionContext(
            provider = SearchProvider.OPENAI,
            model = AgentModelConfig.OPENAI_WEB_SEARCH_MODEL
        )
        val requestPayload = JSONObject()
            .put("model", AgentModelConfig.QWEN_MODEL)
            .put("max_tokens", ANTHROPIC_AGENT_MAX_TOKENS)
            .put("messages", buildOpenAiAgentMessages())
            .put("response_format", JSONObject().put("type", "json_object"))

        val body = requestPayload
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(OPENROUTER_CHAT_COMPLETIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $openRouterApiKey")
            .post(body)
            .build()

        val maxRetries = 2
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val startedAtMs = System.currentTimeMillis()
                return@withContext chatClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string().orEmpty()
                        throw IllegalStateException(
                            AgentApiSupport.formatAgentApiError(
                                providerLabel = "openrouter",
                                model = AgentModelConfig.QWEN_MODEL,
                                statusCode = response.code,
                                responseBody = responseBody,
                                maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                            )
                        )
                    }
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    val usageRecord = recordModelUsage(
                        "openrouter",
                        AgentModelConfig.QWEN_MODEL,
                        responseJson,
                        durationMs = System.currentTimeMillis() - startedAtMs
                    )
                    emitProviderTag("Qwen")
                    attachModelCallId(AgentApiSupport.parseOpenAiChatCompletionText(responseJson), usageRecord)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1 shl attempt)
                    delay(backoffMs)
                }
            }
        }
        throw lastException ?: IllegalStateException("OpenRouter agent call failed")
    }

    private suspend fun callAnthropicAgent(model: String): JSONObject = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY.trim()
        if (apiKey.isBlank()) throw IllegalStateException("Anthropic API key not configured")

        activeSearchContext = SearchExecutionContext(
            provider = SearchProvider.ANTHROPIC,
            model = model
        )
        val requestPayload = JSONObject()
            .put("model", model)
            .put("max_tokens", ANTHROPIC_AGENT_MAX_TOKENS)
            .put(
                "system",
                AgentTooling.systemPrompt(
                    goal = agentState.currentGoal.ifBlank { latestUserMessage },
                    memorySnapshot = memoryPromptSnapshot()
                )
            )
            .put("messages", buildAnthropicAgentMessages())

        val body = requestPayload
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(ANTHROPIC_MESSAGES_ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(body)
            .build()

        val maxRetries = 2
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val startedAtMs = System.currentTimeMillis()
                return@withContext chatClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string().orEmpty()
                        throw IllegalStateException(
                            AgentApiSupport.formatAgentApiError(
                                providerLabel = "anthropic",
                                model = model,
                                statusCode = response.code,
                                responseBody = responseBody,
                                maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                            )
                        )
                    }
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    val usageRecord = recordModelUsage(
                        "anthropic",
                        model,
                        responseJson,
                        durationMs = System.currentTimeMillis() - startedAtMs
                    )
                    emitProviderTag("Anthropic")
                    attachModelCallId(AgentApiSupport.parseAnthropicMessagesApiResult(responseJson), usageRecord)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1 shl attempt)
                    delay(backoffMs)
                }
            }
        }
        throw lastException ?: IllegalStateException("Anthropic agent call failed")
    }

    private suspend fun callOpenAiAgentFallback(
        anthropicError: Exception,
        sourceModel: String = AgentModelConfig.AGENT_MODEL
    ): JSONObject = withContext(Dispatchers.IO) {
        val openAiApiKey = BuildConfig.OPENAI_API_KEY.trim()
        if (openAiApiKey.isBlank()) throw anthropicError

        val fallbackModel = AgentModelConfig.openAiFallbackModelFor(sourceModel)
        activeSearchContext = SearchExecutionContext(
            provider = SearchProvider.OPENAI,
            model = fallbackModel
        )
        val requestPayload = JSONObject()
            .put("model", fallbackModel)
            .put("max_completion_tokens", ANTHROPIC_AGENT_MAX_TOKENS)
            .put("messages", buildOpenAiAgentMessages())
            .put("reasoning_effort", AgentModelConfig.OPENAI_AGENT_REASONING_EFFORT)
            .put("response_format", JSONObject().put("type", "json_object"))

        val body = requestPayload
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(OPENAI_CHAT_COMPLETIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .post(body)
            .build()

        val startedAtMs = System.currentTimeMillis()
        chatClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                val fallbackError = IllegalStateException(
                    AgentApiSupport.formatAgentApiError(
                        providerLabel = "openai",
                        model = fallbackModel,
                        statusCode = response.code,
                        responseBody = responseBody,
                        maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                    )
                )
                fallbackError.addSuppressed(anthropicError)
                throw fallbackError
            }
            val responseJson = JSONObject(response.body?.string().orEmpty())
            val usageRecord = recordModelUsage(
                "openai",
                fallbackModel,
                responseJson,
                callType = "fallback",
                durationMs = System.currentTimeMillis() - startedAtMs
            )
            emitProviderTag("ChatGPT")
            attachModelCallId(AgentApiSupport.parseOpenAiChatCompletionText(responseJson), usageRecord)
        }
    }

    private suspend fun recordModelUsage(
        provider: String,
        model: String,
        responseJson: JSONObject,
        callType: String = "chat",
        durationMs: Long? = null,
        webSearchRequestsOverride: Int? = null
    ): ModelUsageRecordResult? {
        val usage = responseJson.optJSONObject("usage") ?: return null
        val inputTokens = usage.optInt("input_tokens", usage.optInt("prompt_tokens", 0))
        val outputTokens = usage.optInt("output_tokens", usage.optInt("completion_tokens", 0))
        val cachedInputTokens = cachedInputTokensFromUsage(usage)
        val cacheCreationInputTokens = usage.optInt("cache_creation_input_tokens", 0)
        val webSearchRequests = webSearchRequestsOverride
            ?: usage.optJSONObject("server_tool_use")?.optInt("web_search_requests", 0)
            ?: 0
        if (
            inputTokens <= 0 &&
            outputTokens <= 0 &&
            cachedInputTokens <= 0 &&
            cacheCreationInputTokens <= 0 &&
            webSearchRequests <= 0
        ) return null

        val taskId = usageTaskIdProvider()
        return runCatching {
            backendClient.recordModelUsage(
                taskId = taskId,
                provider = provider,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                callType = callType,
                cachedInputTokens = cachedInputTokens,
                cacheCreationInputTokens = cacheCreationInputTokens,
                webSearchRequests = webSearchRequests,
                durationMs = durationMs
            )
        }.onFailure {
            Log.w("AgentManager", "Could not record model usage", it)
        }.getOrNull()
    }

    private fun recordModelUsageAsync(
        provider: String,
        model: String,
        responseJson: JSONObject,
        callType: String = "chat",
        durationMs: Long? = null,
        webSearchRequestsOverride: Int? = null
    ) {
        scope.launch(Dispatchers.IO) {
            recordModelUsage(
                provider,
                model,
                responseJson,
                callType,
                durationMs,
                webSearchRequestsOverride
            )
        }
    }

    private fun cachedInputTokensFromUsage(usage: JSONObject): Int {
        val anthropicCacheRead = usage.optInt("cache_read_input_tokens", 0)
        if (anthropicCacheRead > 0) return anthropicCacheRead

        val promptDetails = usage.optJSONObject("prompt_tokens_details")
        val openAiChatCached = promptDetails?.optInt("cached_tokens", 0) ?: 0
        if (openAiChatCached > 0) return openAiChatCached

        val inputDetails = usage.optJSONObject("input_tokens_details")
        return inputDetails?.optInt("cached_tokens", 0) ?: 0
    }

    private fun recordMemoryReviewUsage(responseJson: JSONObject) {
        recordModelUsageAsync(
            provider = "openai",
            model = AgentModelConfig.MEMORY_SAVE_MODEL,
            responseJson = responseJson,
            callType = "memory_review"
        )
    }

    private fun attachModelCallId(result: JSONObject, usageRecord: ModelUsageRecordResult?): JSONObject {
        val modelCallId = usageRecord?.modelCallId ?: return result
        return result.put("_marvin_model_call_id", modelCallId)
    }

    private suspend fun updateModelCallTools(
        modelCallId: String?,
        executedTools: List<ExecutedChatTool>
    ) {
        val taskId = usageTaskIdProvider() ?: return
        if (modelCallId.isNullOrBlank() || executedTools.isEmpty()) return
        val tools = JSONArray().also { arr ->
            executedTools.forEach { tool ->
                arr.put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("args", runCatching { JSONObject(tool.rawArgs) }.getOrDefault(JSONObject()))
                        .put("success", tool.success)
                        .put("resultSummary", tool.resultDisplay.take(400))
                )
            }
        }
        withContext(Dispatchers.IO) {
            runCatching {
                backendClient.updateModelCallTools(
                    taskId = taskId,
                    modelCallId = modelCallId,
                    toolsCalled = tools
                )
            }.onFailure {
                Log.w("AgentManager", "Could not update model call tools", it)
            }
        }
    }

    private suspend fun updateModelCallToolTelemetry(
        modelCallId: String?,
        toolTelemetry: List<ToolCallTelemetry>
    ) {
        val taskId = usageTaskIdProvider() ?: return
        if (modelCallId.isNullOrBlank() || toolTelemetry.isEmpty()) return
        val tools = JSONArray().also { arr ->
            toolTelemetry.forEach { tool ->
                arr.put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("args", runCatching { JSONObject(tool.rawArgs) }.getOrDefault(JSONObject()))
                        .put("success", tool.success)
                        .put("resultSummary", tool.resultSummary.take(400))
                )
            }
        }
        withContext(Dispatchers.IO) {
            runCatching {
                backendClient.updateModelCallTools(
                    taskId = taskId,
                    modelCallId = modelCallId,
                    toolsCalled = tools
                )
            }.onFailure {
                Log.w("AgentManager", "Could not update model call tools", it)
            }
        }
    }

    private fun buildAgentContextMessage(): String {
        val successfulToolActions = toolCallLog.count { it.status == "success" }
        val completedPlanSteps = agentState.completedSteps.size
        val totalPlanSteps = agentState.plan.size
        val goalStatus = when {
            totalPlanSteps > 0 -> "In progress — $completedPlanSteps of $totalPlanSteps plan steps marked done. Confirm the current screen before acting."
            successfulToolActions == 0 -> "Starting — plan your approach before acting."
            else -> "In progress — $successfulToolActions successful tool actions recorded. Check that you are on the right screen before the next action."
        }
        val goal = agentState.currentGoal.ifBlank { latestUserMessage }
        val knownPackage = agentState.lastTree.values.firstOrNull()?.packageName ?: extractCurrentPackageName()

        return buildString {
            appendLine("## Prompt Context")
            appendLine("### Task Context")
            appendLine("- Goal: ${goal.ifBlank { "(none)" }}")
            appendLine("- Latest user message: ${latestUserMessage.ifBlank { "(none)" }}")
            appendLine("- Goal status: $goalStatus")
            appendLine("- Last UI change: $lastUiChange")
            appendLine("- Current foreground package: ${knownPackage ?: "(unknown)"}")
            appendLine("- Tool arguments: follow each tool description and rely on the latest state. Do not invent values.")
            appendLine("- tap_xy targeting: choose a point clearly inside the intended target. If the target sits above a bottom bar, stay inside the button and above the bar.")
            appendLine("- Last interacted node ref: ${lastTappedNodeRef ?: "(none)"}")
            appendLine("- Last interacted label: ${lastTappedLabel ?: "(none)"}")
            agentState.pendingQuestion?.let { question ->
                appendLine("- Pending user reply: $question")
            }
            detectLoopWarning()?.let { warning ->
                appendLine("- Loop warning: $warning")
            }
            appendLine()
            appendLine("### Agent State")
            appendLine("- Known node refs on latest screen: ${agentState.lastTree.size}")
            appendLine("- Latest screenshot decoded: ${if (agentState.lastScreenshot != null && latestScreenshotRevision == latestScreenRevision) "yes" else "no"}")
            appendLine("- Completed plan step ids: ${if (agentState.completedSteps.isEmpty()) "(none)" else agentState.completedSteps.joinToString(", ")}")
            appendLine()
            appendLine("### Current State")
            appendLine("```json")
            appendLine(
                AgentSessionFormatting.renderStateJson(
                    goal = goal,
                    plan = agentState.plan,
                    nextSteps = agentState.nextSteps,
                    screenObservation = agentState.lastObservation
                )
            )
            appendLine("```")
            appendLine()
        }
    }

    private fun emptyAgentState(currentGoal: String = ""): AgentState {
        return AgentState(
            currentGoal = currentGoal,
            plan = emptyList(),
            nextSteps = emptyList(),
            completedSteps = emptyList(),
            memory = emptyList(),
            lastScreenshot = null,
            lastTree = emptyMap(),
            lastObservation = null,
            needsUserInput = false,
            pendingQuestion = null,
            mapsWasActive = false
        )
    }

    private fun detectLoopWarning(): String? {
        val history = toolCallLog
        if (history.size < 2) return null

        // Detect: same tool+args called 3+ times consecutively
        if (consecutiveSameToolCount >= 3) {
            return "LOOP DETECTED: You have called the same tool with the same arguments $consecutiveSameToolCount times in a row ($lastToolCallSignature). You MUST try a completely different approach, use ask_user, or call task_complete."
        }

        // Detect: openapp called for app already in foreground
        val lastCall = history.lastOrNull()
        if (lastCall?.name == AgentTooling.TOOL_OPEN_APP && lastCall.status == "success") {
            val currentPkg = extractCurrentPackageName()
            if (currentPkg != null && lastCall.details.contains(currentPkg)) {
                return "The app is already open in the foreground. Do NOT call openapp again. Use the existing tree to proceed."
            }
        }

        return null
    }

    /**
     * Returns true if the agent loop should be hard-stopped due to an
     * unrecoverable loop pattern (consecutive identical calls exceeding threshold).
     */
    private fun isHardLoopDetected(): Boolean {
        return consecutiveSameToolCount >= HARD_LOOP_THRESHOLD
    }

    private fun extractCurrentPackageName(): String? {
        agentState.lastTree.values.firstOrNull()?.packageName?.trim()?.ifBlank { null }?.let { return it }
        val tree = latestScreenDump ?: return null
        val firstLine = tree.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!firstLine.startsWith("[App: ") || !firstLine.endsWith("]")) return null
        return firstLine.removePrefix("[App: ").removeSuffix("]").trim().ifBlank { null }
    }

    private fun buildAccessibilityTreeMessage(): String? {
        val tree = latestScreenDump?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            appendLine("## Accessibility Tree Context")
            appendLine("### Latest Accessibility Tree")
            appendLine("```")
            appendLine(tree)
            append("```")
        }
    }

    private fun buildScreenshotContextText(): String {
        return buildString {
            appendLine("## Screenshot Context")
            appendLine("Use this only when the accessibility tree is unclear or when visual confirmation is needed.")
            appendLine("The screenshot shows the underlying foreground app. Ignore the assistant overlay entirely and do not target assistant UI.")
            appendLine("The image may be resized so its long side is 720 pixels, but the aspect ratio and app content are preserved.")
            appendLine("For tap_xy, choose a point clearly inside the intended target, not a point between nearby controls.")
            appendLine("If a floating button sits above a bottom bar, keep the tap in the upper-middle of the button so it stays above the bar below.")
        }.trim()
    }

    private fun buildAnthropicAgentMessages(): JSONArray {
        val content = JSONArray()
            .put(anthropicTextBlock(buildAgentContextMessage()))
            .put(anthropicTextBlock(conversation.toHistoryBlockText()))

        buildAccessibilityTreeMessage()?.let { content.put(anthropicTextBlock(it)) }
        buildAnthropicScreenshotBlocks()?.forEach { block -> content.put(block) }

        return JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", content)
        )
    }

    private fun buildOpenAiAgentMessages(): JSONArray {
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", buildAgentContextMessage()))
            .put(JSONObject().put("type", "text").put("text", conversation.toHistoryBlockText()))

        buildAccessibilityTreeMessage()?.let {
            userContent.put(JSONObject().put("type", "text").put("text", it))
        }
        buildOpenAiScreenshotBlocks()?.forEach { block -> userContent.put(block) }

        return JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        AgentTooling.systemPrompt(
                            goal = agentState.currentGoal.ifBlank { latestUserMessage },
                            memorySnapshot = memoryPromptSnapshot()
                        )
                    )
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", userContent)
            )
    }

    private fun buildOpenAiScreenshotBlocks(): List<JSONObject>? {
        if (latestScreenshotRevision == null || latestScreenshotRevision != latestScreenRevision) {
            return null
        }
        val dataUrl = latestScreenshotDataUrl?.takeIf { it.isNotBlank() } ?: return null
        return listOf(
            JSONObject()
                .put("type", "text")
                .put("text", buildScreenshotContextText()),
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", dataUrl))
        )
    }

    private fun buildAnthropicScreenshotBlocks(): List<JSONObject>? {
        if (latestScreenshotRevision == null || latestScreenshotRevision != latestScreenRevision) {
            return null
        }
        val dataUrl = latestScreenshotDataUrl?.takeIf { it.isNotBlank() } ?: return null
        val imageBlock = anthropicImageBlock(dataUrl) ?: return null
        return listOf(
            anthropicTextBlock(buildScreenshotContextText()),
            imageBlock
        )
    }

    private fun anthropicTextBlock(text: String): JSONObject {
        return JSONObject()
            .put("type", "text")
            .put("text", text)
    }

    private fun anthropicImageBlock(dataUrl: String): JSONObject? {
        val match = DATA_URL_REGEX.find(dataUrl) ?: return null
        return JSONObject()
            .put("type", "image")
            .put("source", JSONObject()
                .put("type", "base64")
                .put("media_type", match.groupValues[1])
                .put("data", match.groupValues[2])
            )
    }

    private fun recordToolOutcome(toolName: String, rawArgs: String, content: JSONObject) {
        val ok = content.optBoolean("ok", false)
        val details = AgentSessionFormatting.buildToolOutcomeDetails(toolName, rawArgs, content)
        toolCallLog.add(
            ToolCallSummary(
                name = toolName,
                status = if (ok) "success" else "failure",
                details = details
            )
        )

        // Track consecutive identical tool calls for hard loop detection
        val signature = "$toolName:${rawArgs.take(200)}"
        if (signature == lastToolCallSignature) {
            consecutiveSameToolCount++
        } else {
            consecutiveSameToolCount = 1
            lastToolCallSignature = signature
        }

        content.optString("screen").takeIf { it.isNotBlank() }?.let {
            latestScreenDump = it
            latestScreenRevision = content.takeIf { json -> json.has("screen_revision") }
                ?.optLong("screen_revision")
                ?.takeIf { revision -> revision >= 0L }
            agentState = agentState.copy(lastTree = ScreenReader.fingerprintsForDump(it))
            if (latestScreenshotRevision != null && latestScreenshotRevision != latestScreenRevision) {
                val previousScreenshotRevision = latestScreenshotRevision
                latestScreenshotDataUrl = null
                latestScreenshotRevision = null
                agentState = agentState.copy(
                    lastScreenshot = null,
                    lastObservation = null
                )
            }
        }
        content.optString("image_data_url").takeIf { it.isNotBlank() }?.let {
            latestScreenshotDataUrl = it
            val imageRevision = content.takeIf { json -> json.has("image_revision") }
                ?.optLong("image_revision")
                ?.takeIf { revision -> revision >= 0L }
            latestScreenshotRevision = imageRevision
            agentState = agentState.copy(
                lastScreenshot = decodeScreenshotDataUrl(it),
                lastObservation = screenObservationFromContent(content, imageRevision)
            )
        }

        if (ok) {
            lastSuccessfulToolName = toolName
            lastUiChange = toolName

            if (
                toolName == AgentTooling.TOOL_TAP_NODE ||
                toolName == AgentTooling.TOOL_SCROLL ||
                toolName == AgentTooling.TOOL_LONG_PRESS_NODE ||
                toolName == AgentTooling.TOOL_TAP_TYPE_TEXT
            ) {
                lastTappedNodeRef = content.optString("matched_node_ref").ifBlank {
                    extractNodeRefFromArgs(rawArgs)
                }
                val baseLabel = content.optString("resolved_label").ifBlank { null }
                lastTappedLabel = if (toolName == AgentTooling.TOOL_SCROLL) {
                    val direction = content.optString("direction").ifBlank { null }
                    when {
                        baseLabel != null && direction != null -> "$baseLabel (${direction})"
                        baseLabel != null -> baseLabel
                        else -> direction
                    }
                } else {
                    baseLabel
                }
            } else if (toolName == AgentTooling.TOOL_TAP_XY) {
                lastTappedNodeRef = null
                val x = content.opt("x")?.toString()?.trim().orEmpty()
                val y = content.opt("y")?.toString()?.trim().orEmpty()
                lastTappedLabel = if (x.isNotBlank() && y.isNotBlank()) {
                    "tap_xy($x, $y)"
                } else {
                    "tap_xy"
                }
            } else if (toolName == AgentTooling.TOOL_SWIPE || toolName == AgentTooling.TOOL_SCROLL_PAGE) {
                lastTappedNodeRef = null
                lastTappedLabel = content.optString("direction").ifBlank { toolName }
            }
        }
    }

    private fun screenObservationFromContent(
        content: JSONObject,
        imageRevision: Long?
    ): ScreenObservationState? {
        val packageName = content.optString("package_name").trim().ifBlank { null }
        val screenshotWidth = content.optInt("width").takeIf { content.has("width") && it > 0 }
        val screenshotHeight = content.optInt("height").takeIf { content.has("height") && it > 0 }
        val screenWidth = content.optInt("screen_width").takeIf { content.has("screen_width") && it > 0 }
        val screenHeight = content.optInt("screen_height").takeIf { content.has("screen_height") && it > 0 }
        val screenLeft = content.optInt("screen_left").takeIf { content.has("screen_left") }
        val screenTop = content.optInt("screen_top").takeIf { content.has("screen_top") }

        if (
            packageName == null &&
            screenshotWidth == null &&
            screenshotHeight == null &&
            screenWidth == null &&
            screenHeight == null &&
            screenLeft == null &&
            screenTop == null
        ) {
            return null
        }

        return ScreenObservationState(
            revision = imageRevision,
            packageName = packageName,
            screenshotWidth = screenshotWidth,
            screenshotHeight = screenshotHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            screenLeft = screenLeft,
            screenTop = screenTop
        )
    }

    private fun updateMissionState(mission: ParsedMission) {
        val cleanedPlan = if (mission.phases.isNotEmpty()) {
            mission.phases.filterNot { it.action.equals("(none)", ignoreCase = true) }
        } else {
            agentState.plan
        }
        val goal = agentState.currentGoal.ifBlank { mission.goal.trim() }.ifBlank { latestUserMessage.trim() }
        agentState = agentState.copy(
            currentGoal = goal,
            plan = cleanedPlan,
            completedSteps = cleanedPlan.filter { it.status == StepStatus.DONE }.map { it.id }
        )
    }

    private fun markPlanCompleted(plan: List<PlanStep>): List<PlanStep> {
        return plan.map { step ->
            if (step.status == StepStatus.FAILED) step else step.copy(status = StepStatus.DONE)
        }
    }

    private fun decodeScreenshotDataUrl(dataUrl: String): Bitmap? {
        val marker = "base64,"
        val startIndex = dataUrl.indexOf(marker)
        if (startIndex == -1) return null

        return runCatching {
            val encoded = dataUrl.substring(startIndex + marker.length)
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun extractNodeRefFromArgs(rawArgs: String): String? {
        return extractNodeRef(runCatching { JSONObject(rawArgs) }.getOrNull())
    }

    private fun extractNodeRef(args: JSONObject?): String? {
        if (args == null) return null
        return args.optString("node_ref").trim().ifBlank { null }
    }

    private fun shouldContinueCurrentAgentTask(): Boolean {
        return agentState.needsUserInput && agentState.currentGoal.isNotBlank()
    }

    private fun appendActionTranscriptGoal(goal: String) {
        appendActionTranscriptEntry(
            title = "Goal",
            bodyMarkdown = goal.trim()
        )
    }

    private fun appendActionTranscriptUserMessage(message: String) {
        appendActionTranscriptEntry(
            title = "User",
            bodyMarkdown = message.trim()
        )
    }

    private fun appendActionTranscriptAssistantMessage(message: String) {
        appendActionTranscriptEntry(
            title = "Assistant",
            bodyMarkdown = message.trim()
        )
    }

    private fun appendActionTranscriptToolResult(
        toolName: String,
        rawArgs: String,
        content: JSONObject
    ) {
        appendActionTranscriptEntry(
            title = "Tool `$toolName`",
            bodyMarkdown = AgentSessionFormatting.buildActionTranscriptToolBody(rawArgs, content)
        )
    }

    private fun appendActionTranscriptEntry(title: String, bodyMarkdown: String) {
        if (bodyMarkdown.isBlank()) return
        evalTranscript.add(
            ActionTranscriptEntry(
                title = title,
                bodyMarkdown = bodyMarkdown
            )
        )
    }

    private fun sanitizeForTts(text: String): String {
        return TtsTextSanitizer.sanitizeAgentText(text)
    }

    companion object {
        private const val ANTHROPIC_MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val OPENAI_CHAT_COMPLETIONS_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val OPENROUTER_CHAT_COMPLETIONS_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val ANTHROPIC_AGENT_MAX_TOKENS = 4096
        private const val CHAT_MAX_TOKENS = 1024
        private const val CHAT_MAX_DIRECT_TOOL_ROUNDS = 6
        private const val WEB_SEARCH_MAX_TOKENS = 1024
        private const val WEB_SEARCH_MAX_USES = 5
        private const val MAX_AGENT_ERROR_BODY_CHARS = 600
        private const val HARD_LOOP_THRESHOLD = 4
        private const val MAX_MEMORY_ENTRIES = 12
        private const val RECENT_MEMORY_LIMIT = 6
        private const val EMPTY_ACTION_RETRY_CAP = 2
        private const val CHAT_EMPTY_RESPONSE_RETRY_CAP = 3
        private val DIRECT_CHAT_SHARED_TOOLS = SharedToolSchemas.chatFunctionTools()
            .map { it.name }
            .toSet()
        private val DATA_URL_REGEX = Regex("""^data:([^;]+);base64,(.+)$""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val USER_FACING_TOOLS = setOf(
            AgentTooling.TOOL_SPEAK, AgentTooling.TOOL_ASK_USER, AgentTooling.TOOL_TASK_COMPLETE
        )
        private val KNOWN_BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser",
            "org.chromium.chrome",
            "com.UCMobile",
            "com.firefox.browser"
        )
    }
}
