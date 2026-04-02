package com.example.mobile_assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

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
    private val screenReader: () -> String?
) {

    private data class SharedChatToolResult(
        val toolName: String,
        val response: String
    )

    interface AgentCallbacks {
        fun onAgentThinkingStarted(userMessage: String)
        fun onAgentThinkingFinished()
        fun onAgentResponse(response: String)
        fun onAgentSpeak(message: String)
        fun onAgentTaskComplete(summary: String)
        fun onAgentAskUser(question: String)
        fun onAgentApiKeyMissing()
        fun onAgentError(message: String? = null)
    }

    private val chatClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val spotifyService = SpotifyService(appContext)
    private val clockToolService = ClockToolService(appContext)
    private val callToolService = CallToolService(appContext)
    private val smsToolService = SmsToolService(appContext)
    private val whatsAppToolService = WhatsAppToolService(
        context = appContext,
        tapSendButton = {
            AssistantAccessibilityService.instance?.let { service ->
                ScreenReader.tapWhatsAppSendButton(service)
            } ?: false
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
        whatsAppToolService = whatsAppToolService
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
        sharedToolExecutor = sharedToolExecutor
    )
    private var chatJob: Job? = null
    private var lastOpenAiApiKey: String? = null
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
    private val actionTranscript = mutableListOf<ActionTranscriptEntry>()
    private val chatHistory = mutableListOf<JSONObject>() // persistent across turns for Q&A
    private var lastUiChange = "none"
    private var lastSuccessfulToolName: String? = null
    private var lastTappedNodeRef: String? = null
    private var lastTappedLabel: String? = null
    private var consecutiveSameToolCount = 0
    private var lastToolCallSignature: String? = null

    fun sendToAgent(message: String) {
        val agentApiKey = agentProviderApiKey()
        if (agentApiKey.isBlank()) {
            callbacks.onAgentApiKeyMissing()
            return
        }

        isPaused = false
        lastOpenAiApiKey = BuildConfig.OPENAI_API_KEY.trim().ifBlank { null }
        lastAgentApiKey = agentApiKey
        chatJob?.cancel()
        callbacks.onAgentThinkingStarted(message)

        if (shouldContinueCurrentAgentTask()) {
            // Route through Haiku so it can update or replace the goal using
            // the user's reply before sending it to the agent.
            val goal = agentState.currentGoal.ifBlank { latestUserMessage }
            val pending = agentState.pendingQuestion
            agentState = agentState.copy(needsUserInput = false, pendingQuestion = null)
            if (pending != null) {
                chatHistory.add(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "The phone agent is mid-task working on: \"$goal\". It asked the user: \"$pending\". The user's reply follows. If the reply answers the question or adds to the task, call use_phone with the complete updated goal. If the reply is a new unrelated request, call use_phone with the new goal instead.")
                )
            }
            capturedInitialScreenDump = screenReader()?.takeIf { it.isNotBlank() }
            latestUserMessage = message
            chatJob = scope.launch {
                runCatching { routeMessage(chatApiKey = agentApiKey, agentApiKey = agentApiKey, message = message) }
                    .onFailure {
                        if (it is CancellationException || isPaused) return@onFailure
                        callbacks.onAgentError(it.message)
                    }
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
                    if (it is CancellationException || isPaused) return@onFailure
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
        // Add user message to chat history
        chatHistory.add(JSONObject().put("role", "user").put("content", message))

        val result = callChat(chatApiKey)
        val content = result.optString("content", "").takeIf { it != "null" }?.trim() ?: ""
        val toolCalls = result.optJSONArray("tool_calls")

        // Check if the model wants to use the phone
        val usePhoneCall = ChatPrompt.findUsePhoneTask(toolCalls)
        val sharedToolResponse = handleSharedChatTool(toolCalls)

        if (sharedToolResponse != null && sharedToolResponse.toolName != SharedToolSchemas.TOOL_SEARCH_WEB) {
            chatHistory.add(JSONObject().put("role", "assistant").put("content", sharedToolResponse.response))
            callbacks.onAgentResponse(sharedToolResponse.response)
        } else if (usePhoneCall != null) {
            val task = usePhoneCall.ifBlank { message }
            chatHistory.add(JSONObject().put("role", "assistant").put("content", "Let me do that on your phone."))
            resetAgentState(task)
            seedInitialScreenDump()
            latestUserMessage = task
            appendActionTranscriptGoal(task)
            runAgentLoop(agentApiKey)
        } else if (sharedToolResponse != null) {
            chatHistory.add(JSONObject().put("role", "assistant").put("content", sharedToolResponse.response))
            callbacks.onAgentResponse(sharedToolResponse.response)
        } else if (content.isNotBlank()) {
            chatHistory.add(JSONObject().put("role", "assistant").put("content", content))
            callbacks.onAgentResponse(sanitizeForTts(content))
        }
    }

    private suspend fun handleSharedChatTool(toolCalls: JSONArray?): SharedChatToolResult? {
        val execution = sharedToolExecutor.executeFirstMatching(toolCalls) ?: return null
        return SharedChatToolResult(
            toolName = execution.toolName,
            response = sanitizeForTts(execution.chatResponse)
        )
    }

    private fun resetAgentState(currentGoal: String) {
        latestScreenDump = null
        latestScreenRevision = null
        latestScreenshotDataUrl = null
        latestScreenshotRevision = null
        agentState = emptyAgentState(currentGoal = currentGoal)
        toolCallLog.clear()
        actionTranscript.clear()
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

    private fun agentProviderApiKey(): String {
        return when (AgentModelConfig.ACTIVE.provider) {
            ModelProvider.OPENAI -> BuildConfig.OPENAI_API_KEY.trim()
            ModelProvider.ANTHROPIC -> ApiKeyStore.getAnthropicKey(appContext)
        }
    }

    private suspend fun callChat(apiKey: String): JSONObject = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        for (msg in chatHistory) {
            messages.put(msg)
        }

        val requestPayload = JSONObject()
            .put("model", ChatPrompt.MODEL)
            .put("max_tokens", CHAT_MAX_TOKENS)
            .put("system", ChatPrompt.instructions())
            .put("messages", messages)
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

        chatClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                throw IllegalStateException("HTTP ${response.code}: $responseBody")
            }
            val responseJson = JSONObject(response.body?.string().orEmpty())
            parseAnthropicChatResult(responseJson)
        }
    }

    private fun parseAnthropicChatResult(responseJson: JSONObject): JSONObject {
        val content = responseJson.optJSONArray("content") ?: JSONArray()
        val textParts = StringBuilder()
        val toolCalls = JSONArray()

        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> textParts.append(block.optString("text"))
                "tool_use" -> {
                    val inputObj = block.optJSONObject("input") ?: JSONObject()
                    toolCalls.put(
                        JSONObject()
                            .put("id", block.optString("id"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", block.optString("name"))
                                    .put("arguments", inputObj.toString())
                            )
                    )
                }
            }
        }

        val result = JSONObject()
            .put("role", "assistant")
            .put("content", textParts.toString().trim().ifBlank { JSONObject.NULL })
        if (toolCalls.length() > 0) {
            result.put("tool_calls", toolCalls)
        }
        return result
    }

    /** Parse the Responses API result into a JSONObject with content + tool_calls. */
    private fun parseResponsesApiResult(responseJson: JSONObject): JSONObject {
        val output = responseJson.optJSONArray("output") ?: JSONArray()
        val textParts = StringBuilder()
        val toolCalls = JSONArray()

        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val type = item.optString("type")

            when (type) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") {
                            textParts.append(part.optString("text"))
                        }
                    }
                }
                "function_call" -> {
                    toolCalls.put(
                        JSONObject()
                            .put("id", item.optString("id"))
                            .put("type", "function")
                            .put("function", JSONObject()
                                .put("name", item.optString("name"))
                                .put("arguments", item.optString("arguments"))
                            )
                    )
                }
            }
        }

        // Strip web_search_preview citation markers like 【4:0†source】
        val cleanText = textParts.toString()
            .replace(Regex("""\u3010[^】]*\u3011"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        val result = JSONObject()
            .put("role", "assistant")
            .put("content", cleanText.ifBlank { JSONObject.NULL })

        if (toolCalls.length() > 0) {
            result.put("tool_calls", toolCalls)
        }

        return result
    }

    private fun parseAnthropicMessagesApiResult(responseJson: JSONObject): JSONObject {
        val content = responseJson.optJSONArray("content") ?: JSONArray()
        val textParts = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") {
                textParts.append(part.optString("text"))
            }
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", textParts.toString().trim().ifBlank { JSONObject.NULL })
    }

    private fun formatAgentApiError(
        provider: ModelProvider,
        model: String,
        statusCode: Int,
        responseBody: String
    ): String {
        val parsed = runCatching { JSONObject(responseBody) }.getOrNull()
        val providerLabel = provider.name.lowercase()
        val requestId = parsed?.optString("request_id").orEmpty().ifBlank { null }
        val apiMessage = parsed?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { null }
        val rawBody = compactApiErrorBody(responseBody)

        return buildString {
            append("Agent API error")
            append(" provider=")
            append(providerLabel)
            append(" model=")
            append(model)
            append(" http=")
            append(statusCode)
            requestId?.let {
                append(" request_id=")
                append(it)
            }
            apiMessage?.let {
                append(" message=")
                append(it)
            }
            if (apiMessage == null && rawBody.isNotBlank()) {
                append(" body=")
                append(rawBody)
            }
        }
    }

    private fun compactApiErrorBody(responseBody: String): String {
        return responseBody
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_AGENT_ERROR_BODY_CHARS)
    }

    private fun performWebSearch(query: String): SearchWebResult {
        val apiKey = lastOpenAiApiKey ?: BuildConfig.OPENAI_API_KEY.trim()
        if (apiKey.isNullOrBlank()) {
            return SearchWebResult(ok = false, error = "API key not available.")
        }

        return try {
            val requestPayload = JSONObject()
                .put("model", ChatPrompt.MODEL)
                .put("input", query)
                .put("tools", JSONArray().put(JSONObject().put("type", "web_search_preview")))

            val body = requestPayload.toString()
                .toRequestBody("application/json".toMediaType())

            val request = okhttp3.Request.Builder()
                .url(OPENAI_RESPONSES_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            chatClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    SearchWebResult(ok = false, error = "HTTP ${response.code}: $responseBody")
                } else {
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    val output = responseJson.optJSONArray("output") ?: JSONArray()
                    val textParts = StringBuilder()
                    for (i in 0 until output.length()) {
                        val item = output.optJSONObject(i) ?: continue
                        if (item.optString("type") == "message") {
                            val content = item.optJSONArray("content") ?: continue
                            for (j in 0 until content.length()) {
                                val part = content.optJSONObject(j) ?: continue
                                if (part.optString("type") == "output_text") {
                                    textParts.append(part.optString("text"))
                                }
                            }
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

    // ─── Agent loop ─────────────────────────────────────────────────────────

    private suspend fun runAgentLoop(apiKey: String) {
        val currentGoal = agentState.currentGoal.ifBlank { latestUserMessage }
        agentState = agentState.copy(currentGoal = currentGoal)
        var hasUserFacingOutput = false

        if (actionTranscript.isEmpty() && currentGoal.isNotBlank()) {
            appendActionTranscriptGoal(currentGoal)
        }

        for (step in 0 until AgentTooling.MAX_AGENT_STEPS) {
            val assistantMessage = callAgentModel(apiKey)

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

            // Show thinking to the user
            val thinking = structuredResponse.thinking
            if (thinking.isNotBlank()) {
                callbacks.onAgentResponse(thinking)
                appendActionTranscriptAssistantMessage(thinking)
                hasUserFacingOutput = true
            }

            // If no actions specified, stop the loop
            val actions = structuredResponse.actions
            if (actions.isEmpty()) {
                break
            }

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

                val result = toolExecutor.executeToolCall(toolCall)
                val resultContent = JSONObject(result.toolMessage.optString("content", "{}"))
                recordToolOutcome(toolName = canonName, rawArgs = rawArgs, content = resultContent)
                appendActionTranscriptToolResult(canonName, rawArgs, resultContent)
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
                    shouldStopLoop = true
                    break
                }

                // Hard loop breaker: if the same tool+args repeated too many times, force stop
                if (isHardLoopDetected()) {
                    callbacks.onAgentResponse("I got stuck in a loop and stopped. Try rephrasing your request or doing part of the task manually first.")
                    hasUserFacingOutput = true
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

            if (shouldStopLoop) break

            if (step == AgentTooling.MAX_AGENT_STEPS - 1) {
                val question = "quick question, I've used all my steps — shall I keep going?"
                callbacks.onAgentAskUser(question)
                appendActionTranscriptAssistantMessage(question)
                agentState = agentState.copy(
                    needsUserInput = true,
                    pendingQuestion = question
                )
                hasUserFacingOutput = true
            }

        }

        if (!hasUserFacingOutput) {
            callbacks.onAgentResponse("I stopped early before finishing. Say \"try again\" to continue.")
        }
    }

    private fun startAgentLoop(apiKey: String) {
        chatJob = scope.launch {
            runCatching { runAgentLoop(apiKey) }
                .onFailure {
                    if (it is CancellationException || isPaused) return@onFailure
                    callbacks.onAgentError(
                        it.message ?: "Agent failed (${AgentModelConfig.ACTIVE.provider.name.lowercase()}/${AgentModelConfig.ACTIVE.model})"
                    )
                }
            callbacks.onAgentThinkingFinished()
        }
    }

    private suspend fun callAgentModel(apiKey: String): JSONObject {
        return when (AgentModelConfig.ACTIVE.provider) {
            ModelProvider.OPENAI -> callOpenAiAgent(apiKey)
            ModelProvider.ANTHROPIC -> callAnthropicAgent(apiKey)
        }
    }

    private suspend fun callOpenAiAgent(apiKey: String): JSONObject = withContext(Dispatchers.IO) {
        val input = JSONArray()

        // Initial user message with context
        input.put(
            JSONObject()
                .put("role", "user")
                .put("content", buildAgentContextMessage())
        )

        buildActionTranscriptMessage()?.let { historyMessage ->
            input.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", historyMessage)
            )
        }

        // Current screen state as a fresh user message (only the latest tree/screenshot)
        buildAccessibilityTreeMessage()?.let { treeMessage ->
            input.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", treeMessage)
            )
        }

        buildScreenshotMessage()?.let { screenshotMessage ->
            input.put(screenshotMessage)
        }

        val requestPayload = JSONObject()
            .put("model", AgentModelConfig.ACTIVE.model)
            .put("instructions", AgentTooling.systemPrompt(agentState.currentGoal.ifBlank { latestUserMessage }))
            .put("input", input)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
            .put("reasoning", JSONObject().put("effort", AGENT_REASONING_EFFORT))
        val body = requestPayload
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(OPENAI_RESPONSES_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val maxRetries = 2
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return@withContext chatClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string().orEmpty()
                        throw IllegalStateException(
                            AgentApiSupport.formatAgentApiError(
                                provider = ModelProvider.OPENAI,
                                model = AgentModelConfig.ACTIVE.model,
                                statusCode = response.code,
                                responseBody = responseBody,
                                maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                            )
                        )
                    }
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    AgentApiSupport.parseResponsesApiResult(responseJson)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1 shl attempt)
                    delay(backoffMs)
                }
            }
        }
        throw lastException!!
    }

    private suspend fun callAnthropicAgent(apiKey: String): JSONObject = withContext(Dispatchers.IO) {
        val requestPayload = JSONObject()
            .put("model", AgentModelConfig.ACTIVE.model)
            .put("max_tokens", ANTHROPIC_AGENT_MAX_TOKENS)
            .put("system", AgentTooling.systemPrompt(agentState.currentGoal.ifBlank { latestUserMessage }))
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
                return@withContext chatClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string().orEmpty()
                        throw IllegalStateException(
                            AgentApiSupport.formatAgentApiError(
                                provider = ModelProvider.ANTHROPIC,
                                model = AgentModelConfig.ACTIVE.model,
                                statusCode = response.code,
                                responseBody = responseBody,
                                maxBodyChars = MAX_AGENT_ERROR_BODY_CHARS
                            )
                        )
                    }
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    AgentApiSupport.parseAnthropicMessagesApiResult(responseJson)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1 shl attempt)
                    delay(backoffMs)
                }
            }
        }
        throw lastException!!
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
            appendLine()
            appendLine("### Recent Memory")
            append(AgentSessionFormatting.renderMemoryMarkdown(agentState.memory, RECENT_MEMORY_LIMIT))
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
            pendingQuestion = null
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

    private fun buildActionTranscriptMessage(): String? {
        return AgentSessionFormatting.buildActionTranscriptMessage(actionTranscript)
    }

    private fun buildScreenshotMessage(): JSONObject? {
        if (latestScreenshotRevision == null || latestScreenshotRevision != latestScreenRevision) {
            return null
        }
        val dataUrl = latestScreenshotDataUrl?.takeIf { it.isNotBlank() } ?: return null
        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "input_text")
                    .put("text", buildScreenshotContextText())
            )
            .put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", dataUrl)
            )

        return JSONObject()
            .put("role", "user")
            .put("content", content)
    }

    private fun buildAnthropicAgentMessages(): JSONArray {
        val content = JSONArray()
            .put(anthropicTextBlock(buildAgentContextMessage()))

        AgentSessionFormatting.buildActionTranscriptMessage(actionTranscript)?.let { content.put(anthropicTextBlock(it)) }
        buildAccessibilityTreeMessage()?.let { content.put(anthropicTextBlock(it)) }
        buildAnthropicScreenshotBlocks()?.forEach { block -> content.put(block) }

        return JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", content)
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

        rememberToolOutcome(toolName, details, content, ok)

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

    private fun rememberToolOutcome(
        toolName: String,
        details: String,
        content: JSONObject,
        ok: Boolean
    ) {
        val action = buildString {
            append(toolName)
            if (details.isNotBlank()) {
                append(": ")
                append(details)
            }
        }
        val resultParts = mutableListOf(if (ok) "success" else "failure")
        content.optString("status").ifBlank { null }?.let { resultParts.add("status=$it") }
        content.optString("wait_status").ifBlank { null }?.let { resultParts.add("wait=$it") }
        content.optString("resolved_label").ifBlank { null }?.let { resultParts.add("label=$it") }
        content.optString("observed_package").ifBlank { null }?.let { resultParts.add("observed_package=$it") }
        content.optString("matched_node_ref").ifBlank { null }?.let { resultParts.add("matched=$it") }
        content.optString("question").ifBlank { null }?.let { resultParts.add("question=$it") }
        content.optString("screen_delta").ifBlank { null }?.let { resultParts.add("delta=$it") }
        content.optString("observation_hint").ifBlank { null }?.let { resultParts.add("hint=$it") }
        content.optString("error_kind").ifBlank { null }?.let { resultParts.add("error_kind=$it") }
        content.optString("recovery_hint").ifBlank { null }?.let { resultParts.add("recovery_hint=$it") }
        content.optString("error").ifBlank { null }?.let { resultParts.add("error=$it") }

        agentState = agentState.copy(
            memory = (agentState.memory + MemoryEntry(
                action = AgentSessionFormatting.compactMarkdownText(action),
                result = AgentSessionFormatting.compactMarkdownText(resultParts.joinToString("; "))
            )).takeLast(MAX_MEMORY_ENTRIES)
        )
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
        return agentState.needsUserInput &&
            agentState.currentGoal.isNotBlank() &&
            actionTranscript.isNotEmpty()
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
        actionTranscript.add(
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
        private const val OPENAI_RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
        private const val ANTHROPIC_MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val AGENT_REASONING_EFFORT = "medium"
        private const val ANTHROPIC_AGENT_MAX_TOKENS = 4096
        private const val CHAT_MAX_TOKENS = 1024
        private const val MAX_AGENT_ERROR_BODY_CHARS = 600
        private const val HARD_LOOP_THRESHOLD = 4
        private const val MAX_MEMORY_ENTRIES = 12
        private const val RECENT_MEMORY_LIMIT = 6
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
