package com.example.mobile_assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File



/**
 * Manages the assistant overlay UI within a view added by the accessibility service.
 * This is NOT an Activity — it operates on an overlay view owned by the service.
 */
class AssistantOverlayController(
    private val service: Context,
    val rootView: View,
    private val windowManager: WindowManager,
    private val onDismiss: () -> Unit,
    private val onCollapse: () -> Unit = {}
) {

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val ASSEMBLY_UPLOAD_ENDPOINT = "https://api.assemblyai.com/v2/upload"
        private const val ASSEMBLY_TRANSCRIPT_ENDPOINT = "https://api.assemblyai.com/v2/transcript"
        private const val ASSEMBLY_STT_MODEL = "universal-2"
        private const val ASSEMBLY_TRANSCRIPT_POLL_INTERVAL_MS = 500L
        private const val ASSEMBLY_TRANSCRIPT_MAX_POLLS = 60
        private const val TTS_BASE_URL = "https://api.deepgram.com/v1/speak"
        private const val TTS_DEFAULT_VOICE = "aura-2-asteria-en"
        private const val TTS_SAMPLE_RATE = 24000

        private const val MIN_SPEECH_DURATION_MS = 200L
        private const val MIN_RECORDING_DURATION_MS = 1500L
        private const val SILENCE_STOP_MS   = 700L
        private const val MAX_RECORDING_MS  = 15_000L
        private const val SPEECH_THRESHOLD  = 1000
        private const val AUDIO_DUCK_FOCUS_GAIN = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    }

    private enum class AudioDuckReason {
        LISTENING,
        ASSISTANT_SPEAKING
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = OkHttpClient()
    private val spotifyService by lazy { SpotifyService(service.applicationContext) }
    private val googleAccountService by lazy { GoogleAccountService(service.applicationContext) }
    private val backendClient by lazy { MobileBackendClient(service.applicationContext) }
    private val audioManager =
        service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val inputMethodManager =
        service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }
    private val audioFocusRequest by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AUDIO_DUCK_FOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        } else {
            null
        }
    }

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var transcriptionJob: Job? = null
    private var amplitudeJob: Job? = null
    private var ttsJob: Job? = null
    private var ttsAudioTrack: AudioTrack? = null
    private var ttsIsPlaying = false

    private val waveView: StaticWaveView = rootView.findViewById(R.id.waveView)
    private val transcriptText: TextView = rootView.findViewById(R.id.transcriptText)
    private val micButton: ImageButton = rootView.findViewById(R.id.micButton)
    private val keyboardButton: ImageButton = rootView.findViewById(R.id.keyboardButton)
    private val cancelListeningButton: TextView = rootView.findViewById(R.id.cancelListeningButton)
    private val textInput: EditText = rootView.findViewById(R.id.textInput)
    private val inputSpacer: View = rootView.findViewById(R.id.inputSpacer)
    private val bottomPanel: View = rootView.findViewById(R.id.bottomPanel)
    private val gradientBg: View = rootView.findViewById(R.id.gradientBg)
    private val rootContainer: View = rootView.findViewById(R.id.rootContainer)

    private val responseCard: View = rootView.findViewById(R.id.responseCard)
    private val chatScrollView: ScrollView = rootView.findViewById(R.id.chatScrollView)
    private val chatLog: LinearLayout = rootView.findViewById(R.id.chatLog)
    private val cardStatusText: TextView = rootView.findViewById(R.id.cardStatusText)
    private val cardWaveView: StaticWaveView = rootView.findViewById(R.id.cardWaveView)
    private val cardMicButton: ImageButton = rootView.findViewById(R.id.cardMicButton)
    private val cardKeyboardButton: ImageButton = rootView.findViewById(R.id.cardKeyboardButton)
    private val cardCancelListeningButton: TextView = rootView.findViewById(R.id.cardCancelListeningButton)
    private val cardTextInput: EditText = rootView.findViewById(R.id.cardTextInput)
    private val cardInputSpacer: View = rootView.findViewById(R.id.cardInputSpacer)

    private val agentManager: AgentManager

    private var isListening = false
    private var isTranscribing = false
    private var isKeyboardMode = false
    private var isCardKeyboardMode = false
    private var lastUserInputUsedKeyboard = false
    private var speechDetectedInCurrentRecording = false
    private var micPulseAnimator: ObjectAnimator? = null
    private var currentImeBottom = 0
    private var recordingStartedAtMs = 0L
    private var speechDurationInCurrentRecordingMs = 0L
    private var isAgentPaused = false
    private var isAgentRunning = false
    private var activeAssistantTaskId: String? = null
    private var assistantTaskRequestSeq = 0L
    private val activeAudioDuckReasons = mutableSetOf<AudioDuckReason>()
    private var hasRequestedAudioDuck = false

    private val isCardOpen: Boolean get() = responseCard.visibility == View.VISIBLE

    init {
        gradientBg.background = createGradient()
        bottomPanel.visibility = View.GONE
        bottomPanel.setOnClickListener { /* consume */ }
        responseCard.setOnClickListener { /* consume so gradient tap-to-dismiss doesn't fire */ }
        setupWindowInsets()

        agentManager = AgentManager(
            appContext = service,
            scope = scope,
            callbacks = object : AgentManager.AgentCallbacks {
                override fun onAgentThinkingStarted(userMessage: String) {
                    appendUserMessage(userMessage)
                    openChatCard()
                    setCardStatus(service.getString(R.string.assist_thinking))
                    isAgentPaused = false
                    isAgentRunning = true
                    updateMicStopState()
                }

                override fun onAgentThinkingFinished() {
                    clearCardStatus()
                    isAgentRunning = false
                    updateMicStopState()
                }

                override fun onAgentResponse(response: String) {
                    appendAssistantMessage(response)
                    speakAssistantResponse(response)
                    completeActiveAssistantTask(finalResponse = response)
                }

                override fun onToolCalled(toolName: String, argsJson: String) {
                    appendToolTag(toolName, argsJson)
                }

                override fun onAgentUiAction(action: AssistantUiAction) {
                    appendAssistantAction(action)
                }

                override fun onAgentSpeak(message: String) {
                    appendAssistantMessage(message)
                    speakAssistantResponse(message)
                    prepareForAskUserInput()
                }

                override fun onAgentTaskComplete(summary: String) {
                    val completionText = if (summary.isBlank()) {
                        service.getString(R.string.assist_task_complete)
                    } else {
                        service.getString(R.string.assist_task_complete_with_summary, summary)
                    }
                    completeActiveAssistantTask(finalResponse = completionText)
                    appendAssistantMessage(completionText)
                    if (summary.isNotBlank()) speakAssistantResponse(summary)
                }

                override fun onAgentAskUser(question: String, autoListen: Boolean) {
                    appendAssistantMessage(question)
                    speakAssistantResponse(question)
                    if (autoListen) prepareForAskUserInput()
                }

                override fun onAgentCutoff(reason: String, message: String) {
                    completeActiveAssistantTask(
                        status = "cutoff",
                        finalResponse = message,
                        cutoffReason = reason
                    )
                    appendAssistantMessage(message)
                    speakAssistantResponse(message)
                }

                override fun onAgentApiKeyMissing() {
                    Toast.makeText(service, service.getString(R.string.assist_key_missing_short), Toast.LENGTH_SHORT).show()
                }

                override fun onAgentProviderTag(providerLabel: String) {
                    appendProviderTag(providerLabel)
                }

                override fun onAgentError(message: String?) {
                    val detail = message?.take(1200) ?: "Unknown error"
                    val errorText = "${service.getString(R.string.assist_response_failed)}: $detail. Say \"try again\" to continue."
                    completeActiveAssistantTask(
                        status = "failed",
                        finalResponse = errorText,
                        metadata = JSONObject().put("errorMessage", detail)
                    )
                    appendAssistantMessage(errorText)
                    Toast.makeText(service, service.getString(R.string.assist_response_failed), Toast.LENGTH_SHORT).show()
                }

            },
            screenReader = {
                val svc = AssistantAccessibilityService.instance
                if (svc != null) ScreenReader.readScreen(svc) else null
            },
            usageTaskIdProvider = { activeAssistantTaskId }
        )

        setupClickListeners()
        setupInputHandlers()
        playEntranceAnimation()
        ensureMicPermissionThenListen()
    }

    fun destroy() {
        micPulseAnimator?.cancel()
        transcriptionJob?.cancel()
        ttsJob?.cancel()
        stopTtsPlayback()
        stopRecordingIfNeeded(deleteOutput = true)
        setAudioDucking(AudioDuckReason.LISTENING, active = false)
        setAudioDucking(AudioDuckReason.ASSISTANT_SPEAKING, active = false)
        scope.cancel()
    }

    // ─── Click listeners ─────────────────────────────────────────────────────

    private fun setupClickListeners() {
        micButton.setOnClickListener { handleMicClick() }
        keyboardButton.setOnClickListener { handleKeyboardToggle() }
        inputSpacer.setOnClickListener { if (!isKeyboardMode) toggleKeyboardMode() }
        textInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !isKeyboardMode) enterBottomKeyboardMode() }
        cancelListeningButton.setOnClickListener { cancelAudioCaptureAndTranscription() }
        cardMicButton.setOnClickListener { onCardMicClicked() }
        cardKeyboardButton.setOnClickListener { toggleCardKeyboardMode() }
        cardInputSpacer.setOnClickListener { if (!isCardKeyboardMode) toggleCardKeyboardMode() }
        cardTextInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !isCardKeyboardMode) enterCardKeyboardMode() }
        cardCancelListeningButton.setOnClickListener { cancelAudioCaptureAndTranscription() }
        gradientBg.setOnClickListener { animateOutThen { onCollapse() } }
    }

    private fun setupInputHandlers() {
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else false
        }
        cardTextInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCardTypedMessage()
                true
            } else false
        }
    }

    private fun submitUserMessage(text: String) {
        scope.launch {
            if (activeAssistantTaskId != null || isAgentRunning) {
                agentManager.sendToAgent(text)
                return@launch
            }
            val requestSeq = ++assistantTaskRequestSeq
            openChatCard()
            agentManager.sendToAgent(text)
            startAssistantTaskInBackground(text, requestSeq)
        }
    }

    private fun startAssistantTaskInBackground(text: String, requestSeq: Long) {
        scope.launch {
            when (
                val result = backendClient.startAssistantTask(
                    userPrompt = text,
                    taskType = "hybrid",
                    mainModel = AgentModelConfig.AGENT_MODEL,
                    metadata = JSONObject()
                )
            ) {
                is AssistantTaskStartResult.Allowed -> {
                    if (requestSeq == assistantTaskRequestSeq && isAgentRunning) {
                        activeAssistantTaskId = result.taskId
                    } else {
                        runCatching {
                            backendClient.finishAssistantTask(
                                taskId = result.taskId,
                                status = "completed",
                                cutoffReason = "assistant_finished_before_task_start_returned"
                            )
                        }
                    }
                }
                is AssistantTaskStartResult.OverLimit -> {
                    appendAssistantMessage(service.getString(R.string.account_limit_reached))
                }
                is AssistantTaskStartResult.Failed -> {
                    android.util.Log.w("AssistantOverlayController", "Could not start assistant task: ${result.message}")
                }
            }
        }
    }

    private fun completeActiveAssistantTask(
        status: String = "completed",
        finalResponse: String? = null,
        cutoffReason: String? = null,
        metadata: JSONObject? = null
    ) {
        val taskId = activeAssistantTaskId ?: return
        activeAssistantTaskId = null
        scope.launch {
            runCatching {
                backendClient.finishAssistantTask(
                    taskId = taskId,
                    status = status,
                    finalResponse = finalResponse,
                    cutoffReason = cutoffReason,
                    metadata = metadata
                )
            }
        }
    }

    private fun setupWindowInsets() {
        val bottomSpacer: View = rootView.findViewById(R.id.bottomSpacer)
        val cardBottomSpacer: View = rootView.findViewById(R.id.cardBottomSpacer)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeOffset = if (imeVisible) (ime.bottom - navBar.bottom).coerceAtLeast(0) else 0

            val screenHeight = if (view.height > 0) view.height else service.resources.displayMetrics.heightPixels
            val panelLp = bottomPanel.layoutParams
            val gradientLp = gradientBg.layoutParams as FrameLayout.LayoutParams

            if (imeVisible) {
                val visibleAboveKeyboard = (screenHeight - imeOffset).coerceAtLeast(0)
                panelLp.height = visibleAboveKeyboard / 2
                bottomPanel.translationY = -imeOffset.toFloat()
                gradientLp.height = visibleAboveKeyboard
            } else {
                panelLp.height = (screenHeight * 0.55f).toInt()
                bottomPanel.translationY = 0f
                gradientLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            }

            bottomPanel.layoutParams = panelLp
            gradientBg.layoutParams = gradientLp

            val spacerHeight = (navBar.bottom.coerceAtLeast(dp(24)) / 2).coerceAtLeast(dp(12))
            bottomSpacer.layoutParams.height = spacerHeight
            bottomSpacer.requestLayout()
            cardBottomSpacer.layoutParams.height = spacerHeight
            cardBottomSpacer.requestLayout()

            currentImeBottom = imeOffset
            responseCard.translationY = -imeOffset.toFloat()
            if (isCardOpen) {
                constrainCardHeight()
            }

            insets
        }

        rootContainer.post { ViewCompat.requestApplyInsets(rootContainer) }
    }


    /** Eval harness entry point: run the task through the phone-agent loop directly. */
    fun submitEvalPrompt(text: String) {
        cancelAudioCaptureAndTranscription()
        agentManager.runPhoneTaskDirectly(text)
    }

    /** Long-press-power (assist) gesture while the overlay is already visible: open the mic. */
    fun onAssistGestureTriggered() {
        if (isListening) return
        if (isAgentRunning) {
            agentManager.pauseAgent()
            completeActiveAssistantTask(
                status = "cancelled",
                cutoffReason = "user_paused_agent"
            )
            isAgentRunning = false
            isAgentPaused = false
            clearCardStatus()
            updateMicStopState()
        }
        if (isCardKeyboardMode) exitCardKeyboardMode()
        if (isKeyboardMode) toggleKeyboardMode()
        resetTranscript()
        ensureMicPermissionThenListen()
    }

    // ─── Bottom-panel input ──────────────────────────────────────────────────

    private fun sendTypedMessage(): Boolean {
        val text = getTrimmedInput(textInput)
        if (text.isBlank()) return false
        lastUserInputUsedKeyboard = true
        textInput.text?.clear()
        hideKeyboard(textInput)
        textInput.clearFocus()
        submitUserMessage(text)
        return true
    }

    private fun handleMicClick() {
        if (isAgentRunning) {
            agentManager.pauseAgent()
            completeActiveAssistantTask(
                status = "cancelled",
                cutoffReason = "user_paused_agent"
            )
            isAgentRunning = false
            isAgentPaused = false
            clearCardStatus()
            updateMicStopState()
            return
        }
        if (isKeyboardMode) {
            sendTypedMessage()
            return
        }
        if (isListening) {
            stopListening()
            return
        }
        resetTranscript()
        ensureMicPermissionThenListen()
    }

    private fun handleKeyboardToggle() {
        if (isKeyboardMode) {
            toggleKeyboardMode()
            resetTranscript()
            ensureMicPermissionThenListen()
        } else {
            toggleKeyboardMode()
        }
    }

    // ─── Recording lifecycle ─────────────────────────────────────────────────

    private fun cancelListening() {
        if (!isListening) return
        isListening = false
        onListeningStateChanged(false)
        stopRecordingIfNeeded(deleteOutput = true)
        setAudioDucking(AudioDuckReason.LISTENING, active = false)
        recordingStartedAtMs = 0L
        updateTranscript("")
    }

    private fun cancelAudioCaptureAndTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        isTranscribing = false
        if (isListening) {
            isListening = false
            onListeningStateChanged(false)
        }
        stopRecordingIfNeeded(deleteOutput = true)
        setAudioDucking(AudioDuckReason.LISTENING, active = false)
        recordingStartedAtMs = 0L
        updateTranscript("")
        updateCancelButtonsVisibility()
    }

    private fun resetTranscript() {
        updateTranscript("")
    }

    private fun ensureMicPermissionThenListen() {
        ttsJob?.cancel()
        stopTtsPlayback()

        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            // Mic permission must already be granted before the overlay is shown.
            // The accessibility service cannot launch a permission dialog.
            Toast.makeText(service, service.getString(R.string.assist_mic_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startListening() {
        if (isListening) return
        if (isKeyboardMode) toggleKeyboardMode()

        transcriptionJob?.cancel()
        speechDetectedInCurrentRecording = false
        speechDurationInCurrentRecordingMs = 0L

        runCatching {
            val outputFile = createRecordingFile()
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("NewApi")
                MediaRecorder(service)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(64_000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            recordingFile = outputFile
            mediaRecorder = recorder
        }.onSuccess {
            recordingStartedAtMs = System.currentTimeMillis()
            isListening = true
            setAudioDucking(AudioDuckReason.LISTENING, active = true)
            onListeningStateChanged(true)
            startAmplitudeUpdates()
        }.onFailure { throwable ->
            stopRecordingIfNeeded(deleteOutput = true)
            recordingStartedAtMs = 0L
            isListening = false
            setAudioDucking(AudioDuckReason.LISTENING, active = false)
            onListeningStateChanged(false)
            Toast.makeText(service, service.getString(R.string.assist_listening_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopListening(force: Boolean = false) {
        if (!isListening) return

        // Don't stop until minimum recording duration has elapsed (unless forced)
        if (!force && recordingStartedAtMs > 0) {
            val elapsed = System.currentTimeMillis() - recordingStartedAtMs
            if (elapsed < MIN_RECORDING_DURATION_MS) return
        }

        isListening = false
        setAudioDucking(AudioDuckReason.LISTENING, active = false)
        onListeningStateChanged(false)

        val audioFile = stopRecordingIfNeeded(deleteOutput = false)
        recordingStartedAtMs = 0L

        if (audioFile == null) {
            Toast.makeText(service, service.getString(R.string.assist_listening_failed), Toast.LENGTH_SHORT).show()
            return
        }
        if (speechDurationInCurrentRecordingMs in 1..MIN_SPEECH_DURATION_MS) {
            cleanupAudioFile(audioFile)
            updateTranscript("")
            Toast.makeText(service, service.getString(R.string.assist_transcription_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        if (!speechDetectedInCurrentRecording) {
            cleanupAudioFile(audioFile)
            updateTranscript("")
            Toast.makeText(service, service.getString(R.string.assist_no_speech_detected), Toast.LENGTH_SHORT).show()
            return
        }

        transcribeAudio(audioFile)
    }

    private fun createRecordingFile(): File = File.createTempFile("stt_", ".m4a", service.cacheDir)

    private fun stopRecordingIfNeeded(deleteOutput: Boolean): File? {
        stopAmplitudeUpdates()

        val recorder = mediaRecorder
        val file = recordingFile

        mediaRecorder = null
        recordingFile = null

        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }

        if (deleteOutput) {
            cleanupAudioFile(file)
            return null
        }

        return file?.takeIf { it.exists() && it.length() > 0L }
    }

    // ─── Transcription ───────────────────────────────────────────────────────

    private fun transcribeAudio(audioFile: File) {
        val apiKey = BuildConfig.ASSEMBLY_AI_API_KEY.trim()
        if (apiKey.isBlank()) {
            cleanupAudioFile(audioFile)
            Toast.makeText(service, service.getString(R.string.assist_stt_key_missing), Toast.LENGTH_SHORT).show()
            return
        }

        transcriptionJob?.cancel()
        transcriptionJob = scope.launch {
            isTranscribing = true
            updateCancelButtonsVisibility()
            updateTranscript(service.getString(R.string.assist_transcribing))

            try {
                val transcript = runCatching {
                    transcribeWithAssemblyAi(apiKey, audioFile)
                }.getOrDefault("")

                if (!isActive) return@launch

                if (transcript.isBlank()) {
                    Toast.makeText(service, service.getString(R.string.assist_transcription_failed), Toast.LENGTH_SHORT).show()
                    updateTranscript("")
                    return@launch
                }

                updateTranscript(transcript)
                lastUserInputUsedKeyboard = false
                submitUserMessage(transcript)
            } finally {
                cleanupAudioFile(audioFile)
                isTranscribing = false
                updateCancelButtonsVisibility()
            }
        }
    }

    private suspend fun transcribeWithAssemblyAi(apiKey: String, audioFile: File): String {
        return withContext(Dispatchers.IO) {
            val uploadUrl = uploadAudioToAssemblyAi(apiKey, audioFile)
            val transcriptId = submitAssemblyAiTranscript(apiKey, uploadUrl)
            pollAssemblyAiTranscript(apiKey, transcriptId)
        }
    }

    private fun uploadAudioToAssemblyAi(apiKey: String, audioFile: File): String {
        val requestBody = audioFile.readBytes().toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url(ASSEMBLY_UPLOAD_ENDPOINT)
            .addHeader("Authorization", apiKey)
            .addHeader("Content-Type", "application/octet-stream")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("AssemblyAI upload HTTP ${response.code}: $body")
            }
            return JSONObject(body).getString("upload_url")
        }
    }

    private fun submitAssemblyAiTranscript(apiKey: String, uploadUrl: String): String {
        val requestBody = JSONObject()
            .put("audio_url", uploadUrl)
            .put("speech_models", JSONArray().put(ASSEMBLY_STT_MODEL))
            .put("format_text", true)
            .put("punctuate", true)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(ASSEMBLY_TRANSCRIPT_ENDPOINT)
            .addHeader("Authorization", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("AssemblyAI transcript HTTP ${response.code}: $body")
            }
            return JSONObject(body).getString("id")
        }
    }

    private suspend fun pollAssemblyAiTranscript(apiKey: String, transcriptId: String): String {
        val request = Request.Builder()
            .url("$ASSEMBLY_TRANSCRIPT_ENDPOINT/$transcriptId")
            .addHeader("Authorization", apiKey)
            .build()

        repeat(ASSEMBLY_TRANSCRIPT_MAX_POLLS) {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("AssemblyAI transcript poll HTTP ${response.code}: $body")
                }
                val json = JSONObject(body)
                when (json.optString("status")) {
                    "completed" -> return json.optString("text").trim()
                    "error" -> throw IllegalStateException(
                        json.optString("error").ifBlank { "AssemblyAI transcription failed." }
                    )
                }
            }
            delay(ASSEMBLY_TRANSCRIPT_POLL_INTERVAL_MS)
        }

        throw IllegalStateException("AssemblyAI transcription timed out.")
    }

    private fun cleanupAudioFile(file: File?) {
        if (file == null || !file.exists()) return
        runCatching { file.delete() }
    }

    // ─── Text-to-speech (Deepgram text-to-speech) ──────────────────────────────────

    private fun speakAssistantResponse(text: String) {
        if (lastUserInputUsedKeyboard) return
        if (text.isBlank()) return

        val apiKey = BuildConfig.DEEPGRAM_API_KEY.trim()
        if (apiKey.isBlank()) return

        ttsJob?.cancel()
        stopTtsPlayback()
        setAudioDucking(AudioDuckReason.ASSISTANT_SPEAKING, active = true)

        ttsJob = scope.launch {
            try {
                runCatching { streamDeepgramTts(apiKey, text) }
            } finally {
                ttsIsPlaying = false
                setAudioDucking(AudioDuckReason.ASSISTANT_SPEAKING, active = false)
            }
        }
    }

    private suspend fun streamDeepgramTts(apiKey: String, text: String) = withContext(Dispatchers.IO) {
        val ttsPrefs = service.getSharedPreferences("marvin_prefs", android.content.Context.MODE_PRIVATE)
        val voice = ttsPrefs.getString("pref_tts_voice", TTS_DEFAULT_VOICE) ?: TTS_DEFAULT_VOICE
        val speed = ttsPrefs.getFloat("pref_tts_speed", 1.0f)
        val speedParam = "%.2f".format(speed)
        val ttsUrl = "$TTS_BASE_URL?model=$voice&encoding=linear16&sample_rate=24000&container=none&speed=$speedParam"

        val requestBody = JSONObject()
            .put("text", text)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(ttsUrl)
            .addHeader("Authorization", "Token $apiKey")
            .post(requestBody)
            .build()

        val bufferSize = AudioTrack.getMinBufferSize(
            TTS_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TTS_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        ttsAudioTrack = audioTrack
        ttsIsPlaying = true
        audioTrack.play()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    throw IllegalStateException("HTTP ${response.code}: $errorBody")
                }
                response.body?.byteStream()?.use { stream ->
                    val buffer = ByteArray(4096)
                    var n: Int
                    while (stream.read(buffer).also { n = it } != -1) {
                        audioTrack.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            runCatching {
                audioTrack.stop()
                audioTrack.release()
            }
            if (ttsAudioTrack === audioTrack) {
                ttsAudioTrack = null
            }
        }
    }

    private fun stopTtsPlayback() {
        val track = ttsAudioTrack
        ttsAudioTrack = null
        ttsIsPlaying = false
        setAudioDucking(AudioDuckReason.ASSISTANT_SPEAKING, active = false)
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private fun setAudioDucking(reason: AudioDuckReason, active: Boolean) {
        val changed = if (active) {
            activeAudioDuckReasons.add(reason)
        } else {
            activeAudioDuckReasons.remove(reason)
        }
        if (!changed) return
        updateAudioDucking()
    }

    private fun updateAudioDucking() {
        val shouldDuck = activeAudioDuckReasons.isNotEmpty()
        if (shouldDuck == hasRequestedAudioDuck) return

        if (shouldDuck) {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = audioFocusRequest ?: return
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AUDIO_DUCK_FOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            hasRequestedAudioDuck = granted
            if (!granted) {
                activeAudioDuckReasons.clear()
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasRequestedAudioDuck = false
    }

    // ─── Amplitude + silence detection ───────────────────────────────────────

    private fun startAmplitudeUpdates() {
        stopAmplitudeUpdates()
        val startTime = System.currentTimeMillis()
        var lastSpeechTime = startTime
        var hasMetMinimumSpeechDuration = false
        var lastSampleTime = startTime

        amplitudeJob = scope.launch {
            while (isActive && isListening) {
                val raw = mediaRecorder?.maxAmplitude ?: 0

                val visual = if (raw > 0) {
                    val log = (Math.log10(raw.toDouble()) / Math.log10(32767.0)).coerceIn(0.0, 1.0)
                    Math.sqrt(log).toFloat()
                } else 0f

                waveView.updateAmplitude(visual)
                if (isCardOpen) updateCardWaveAmplitude(visual)

                val now = System.currentTimeMillis()
                val sampleDuration = now - lastSampleTime
                lastSampleTime = now
                if (raw >= SPEECH_THRESHOLD) {
                    speechDurationInCurrentRecordingMs += sampleDuration
                    hasMetMinimumSpeechDuration =
                        speechDurationInCurrentRecordingMs > MIN_SPEECH_DURATION_MS
                    speechDetectedInCurrentRecording = true
                    lastSpeechTime = now
                }

                val elapsed = now - startTime
                val silence = now - lastSpeechTime
                when {
                    elapsed >= MAX_RECORDING_MS -> stopListening(force = true)
                    elapsed >= MIN_RECORDING_DURATION_MS &&
                        hasMetMinimumSpeechDuration &&
                        silence >= SILENCE_STOP_MS -> stopListening()
                }

                delay(100L)
            }
        }
    }

    private fun stopAmplitudeUpdates() {
        amplitudeJob?.cancel()
        amplitudeJob = null
    }

    // ─── Listening state ─────────────────────────────────────────────────────

    private fun onListeningStateChanged(active: Boolean) {
        updateCancelButtonsVisibility()
        if (active) {
            if (isCardOpen) showCardListeningState()
            waveView.start()
            startMicPulse()
        } else {
            if (isCardOpen) hideCardListeningState()
            waveView.stop()
            stopMicPulse()
        }
    }

    private fun updateTranscript(text: String) {
        if (isCardOpen) {
            setCardStatus(text)
        } else {
            if (text.isBlank()) {
                transcriptText.visibility = View.GONE
            } else {
                transcriptText.text = text
                transcriptText.visibility = View.VISIBLE
            }
        }
    }

    private fun updateCancelButtonsVisibility() {
        val show = isListening || isTranscribing
        cancelListeningButton.visibility = if (show) View.VISIBLE else View.GONE
        cardCancelListeningButton.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ─── Bottom-panel keyboard mode ──────────────────────────────────────────

    private fun toggleKeyboardMode() {
        isKeyboardMode = !isKeyboardMode
        if (isKeyboardMode) enterBottomKeyboardMode() else exitBottomKeyboardMode()
    }

    /**
     * To allow keyboard input inside a FLAG_NOT_FOCUSABLE overlay, we
     * temporarily clear FLAG_NOT_FOCUSABLE so the IME can target our
     * EditText, then restore it when the user exits keyboard mode.
     */
    private fun setOverlayFocusable(focusable: Boolean) {
        val lp = rootView.layoutParams as? WindowManager.LayoutParams ?: return
        if (focusable) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(rootView, lp) }
        ViewCompat.requestApplyInsets(rootContainer)
    }

    // ─── Chat card ───────────────────────────────────────────────────────────

    private fun onCardMicClicked() {
        if (isAgentRunning) {
            agentManager.pauseAgent()
            isAgentRunning = false
            isAgentPaused = false
            clearCardStatus()
            updateMicStopState()
            return
        }
        when {
            isCardKeyboardMode -> {
                val typedText = cardTextInput.text?.toString()?.trim().orEmpty()
                if (typedText.isNotBlank()) {
                    sendCardTypedMessage()
                } else {
                    exitCardKeyboardMode()
                    resetTranscript()
                    ensureMicPermissionThenListen()
                }
            }
            isListening -> stopListening()
            else -> {
                resetTranscript()
                ensureMicPermissionThenListen()
            }
        }
    }

    private fun openChatCard() {
        if (isCardOpen) return
        val baseY = -currentImeBottom.toFloat()
        responseCard.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = baseY + dp(16).toFloat()
            animate()
                .alpha(1f)
                .translationY(baseY)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun updateMicStopState() {
        // The bottom panel mic button always shows the idle mic icon so the
        // square looks finished and ready regardless of agent state.
        micButton.setImageResource(R.drawable.ic_mic)
        micButton.imageTintList = tint(R.color.assistant_on_accent)
        setSquareButtonSize(micButton, 56)
        // The in-card mic button shows stop while the agent is running.
        if (isAgentRunning) {
            cardMicButton.setImageResource(R.drawable.ic_stop)
            cardMicButton.imageTintList = tint(R.color.assistant_on_accent)
        } else {
            cardMicButton.setImageResource(R.drawable.ic_mic)
            cardMicButton.imageTintList = tint(R.color.assistant_on_accent)
        }
    }

    private fun appendUserMessage(text: String) {
        addChatBubble(text, color(R.color.assistant_user_bubble_text), 14f, color(R.color.assistant_user_bubble_bg), 1.4f) {
            width = LinearLayout.LayoutParams.WRAP_CONTENT
            gravity = Gravity.END
            setMargins(dp(52), dp(5), 0, dp(5))
        }.also { it.maxWidth = (service.resources.displayMetrics.widthPixels * 0.78).toInt() }
    }

    private fun appendAssistantMessage(text: String) {
        addChatBubble(text, color(R.color.assistant_bot_bubble_text), 14f, color(R.color.assistant_bot_bubble_bg), 1.5f) {
            width = LinearLayout.LayoutParams.MATCH_PARENT
            setMargins(0, dp(5), dp(52), dp(5))
        }
    }

    private fun appendToolTag(toolName: String, argsJson: String) {
        val chip = ToolChipCatalog.chipFor(toolName, argsJson) ?: return
        val tagView = TextView(service).apply {
            text = chip.label
            setTextColor(color(R.color.assistant_code_text))
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(12), dp(5))
            background = roundedBubble(color(R.color.assistant_code_bg))
            ContextCompat.getDrawable(service, chip.iconRes)?.mutate()?.let { icon ->
                val accent = color(chip.tintRes)
                icon.setTint(accent)
                val tile = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
                val tileDrawable = LayerDrawable(arrayOf(tile, icon)).apply {
                    setLayerInset(1, dp(4), dp(4), dp(4), dp(4))
                    setBounds(0, 0, dp(22), dp(22))
                }
                setCompoundDrawables(tileDrawable, null, null, null)
                compoundDrawablePadding = dp(7)
            }
        }
        tagView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(2), dp(52), dp(2))
        }
        chatLog.addView(tagView)
        tagView.alpha = 0f
        tagView.translationY = dp(8).toFloat()
        tagView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        constrainCardHeight()
    }

    private fun appendProviderTag(providerLabel: String) {
        val tagView = TextView(service).apply {
            text = "using $providerLabel"
            setTextColor(color(R.color.assistant_code_text))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundedBubble(color(R.color.assistant_code_bg))
        }
        tagView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(2), dp(52), dp(2))
        }
        chatLog.addView(tagView)
        tagView.alpha = 0f
        tagView.translationY = dp(8).toFloat()
        tagView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        constrainCardHeight()
    }

    private fun appendAssistantAction(action: AssistantUiAction) {
        val button = TextView(service).apply {
            text = action.label
            setTextColor(color(R.color.home_button_primary_text))
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            paint.isFakeBoldText = true
            background = roundedBubble(color(R.color.home_button_primary))
            setOnClickListener { launchAssistantAction(action) }
        }
        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(2), dp(52), dp(8))
        }
        chatLog.addView(button)
        button.alpha = 0f
        button.translationY = dp(10).toFloat()
        button.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        constrainCardHeight()
    }

    private fun launchAssistantAction(action: AssistantUiAction) {
        when (action.type) {
            AssistantUiActionType.CONNECT_SPOTIFY -> {
                val launchResult = spotifyService.createLoginIntent()
                launchOAuthIntent(
                    intent = launchResult.intent,
                    ok = launchResult.ok,
                    message = launchResult.message,
                    noBrowserMessageRes = R.string.spotify_no_browser_found
                )
            }
            AssistantUiActionType.CONNECT_GOOGLE -> {
                val launchResult = googleAccountService.createLoginIntent()
                launchOAuthIntent(
                    intent = launchResult.intent,
                    ok = launchResult.ok,
                    message = launchResult.message,
                    noBrowserMessageRes = R.string.google_no_browser_found
                )
            }
            AssistantUiActionType.OPEN_SETTINGS -> launchSettingsAction(action)
        }
    }

    private fun launchSettingsAction(action: AssistantUiAction) {
        val intentAction = action.intentAction?.takeIf { it.isNotBlank() }
        if (intentAction == null) {
            Toast.makeText(service, action.label, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(intentAction).apply {
            action.dataUri?.takeIf { it.isNotBlank() }?.let { data = Uri.parse(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(android.provider.Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            val launchIntent = if (intent.resolveActivity(service.packageManager) != null) {
                intent
            } else {
                fallback
            }
            service.startActivity(launchIntent)
        }.onFailure {
            Toast.makeText(service, action.label, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchOAuthIntent(
        intent: Intent?,
        ok: Boolean,
        message: String,
        noBrowserMessageRes: Int
    ) {
        if (!ok || intent == null) {
            Toast.makeText(service, message, Toast.LENGTH_LONG).show()
            return
        }
        if (intent.resolveActivity(service.packageManager) == null) {
            Toast.makeText(service, service.getString(noBrowserMessageRes), Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(service, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun appendCompletionMessage(text: String) {
        addChatBubble(text, color(R.color.assistant_completion_text), 13f, color(R.color.assistant_completion_bg), 1.35f) {
            width = LinearLayout.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(dp(16), dp(6), dp(16), dp(8))
        }
    }

    private fun addChatBubble(
        text: String, textColor: Int, size: Float, bgColor: Int, lineSpacingMult: Float,
        layoutConfig: LinearLayout.LayoutParams.() -> Unit
    ): TextView {
        val bubble = TextView(service).apply {
            this.text = text
            setTextColor(textColor)
            textSize = size
            setLineSpacing(0f, lineSpacingMult)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBubble(bgColor)
        }
        bubble.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply(layoutConfig)
        chatLog.addView(bubble)
        bubble.alpha = 0f
        bubble.translationY = dp(12).toFloat()
        bubble.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        constrainCardHeight()
        return bubble
    }

    private fun prepareForAskUserInput() {
        openChatCard()
        val preferKeyboard = lastUserInputUsedKeyboard || isCardKeyboardMode || isKeyboardMode
        if (preferKeyboard) {
            if (!isCardKeyboardMode) enterCardKeyboardMode()
            cardTextInput.post {
                cardTextInput.requestFocus()
                showKeyboard(cardTextInput)
            }
            return
        }

        scope.launch {
            waitForTtsToFinish(maxWaitMs = 8_000L)
            resetTranscript()
            ensureMicPermissionThenListen()
        }
    }

    private suspend fun waitForTtsToFinish(maxWaitMs: Long) {
        var waitedMs = 0L
        while (waitedMs < maxWaitMs) {
            val hasPendingTts =
                (ttsJob?.isActive == true) || ttsIsPlaying
            if (!hasPendingTts) return
            delay(120L)
            waitedMs += 120L
        }
    }

    private fun constrainCardHeight() {
        val svLp = chatScrollView.layoutParams
        if (svLp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
            svLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            chatScrollView.layoutParams = svLp
        }
        responseCard.post {
            val availH = service.resources.displayMetrics.heightPixels - currentImeBottom
            val maxCardH = (availH * 0.55).toInt()
            if (responseCard.height > maxCardH) {
                val overflow = responseCard.height - maxCardH
                val lp = chatScrollView.layoutParams
                lp.height = (chatScrollView.height - overflow).coerceAtLeast(80)
                chatScrollView.layoutParams = lp
                chatScrollView.requestLayout()
            }
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setCardStatus(text: String) {
        if (text.isBlank()) clearCardStatus()
        else cardStatusText.apply { this.text = text; visibility = View.VISIBLE }
    }

    private fun clearCardStatus() {
        cardStatusText.visibility = View.GONE
    }

    private fun showCardListeningState() {
        cardStatusText.visibility = View.GONE
        cardWaveView.visibility = View.VISIBLE
        cardWaveView.start()
    }

    private fun hideCardListeningState() {
        cardWaveView.stop()
        cardWaveView.visibility = View.GONE
    }

    private fun updateCardWaveAmplitude(amplitude: Float) {
        cardWaveView.updateAmplitude(amplitude)
    }

    private fun toggleCardKeyboardMode() {
        if (isCardKeyboardMode) exitCardKeyboardMode() else enterCardKeyboardMode()
    }

    private fun enterCardKeyboardMode() {
        isCardKeyboardMode = true
        cancelListening()

        cardInputSpacer.visibility = View.GONE
        cardTextInput.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
            requestFocus()
        }

        setOverlayFocusable(true)
        cardTextInput.postDelayed({ showKeyboard(cardTextInput) }, 150)

        cardKeyboardButton.setImageResource(R.drawable.ic_mic)
        cardKeyboardButton.imageTintList = tint(R.color.assistant_hint)
        cardMicButton.setImageResource(R.drawable.ic_paper_plane)
    }

    private fun exitCardKeyboardMode() {
        isCardKeyboardMode = false

        cardTextInput.visibility = View.GONE
        cardInputSpacer.visibility = View.VISIBLE
        cardTextInput.text?.clear()

        hideKeyboard(cardTextInput)
        setOverlayFocusable(false)

        cardKeyboardButton.setImageResource(R.drawable.ic_keyboard)
        cardKeyboardButton.imageTintList = tint(R.color.assistant_hint)
        cardMicButton.setImageResource(R.drawable.ic_mic)
        cardMicButton.imageTintList = tint(R.color.assistant_on_accent)
    }

    private fun sendCardTypedMessage() {
        val text = getTrimmedInput(cardTextInput)
        if (text.isBlank()) return
        lastUserInputUsedKeyboard = true
        cardTextInput.text?.clear()
        hideKeyboard(cardTextInput)
        cardTextInput.clearFocus()
        submitUserMessage(text)
    }

    private fun enterBottomKeyboardMode() {
        cancelListening()

        inputSpacer.visibility = View.GONE
        textInput.visibility = View.VISIBLE
        textInput.alpha = 0f
        textInput.animate().alpha(1f).setDuration(200).start()
        textInput.requestFocus()

        setOverlayFocusable(true)
        textInput.postDelayed({ showKeyboard(textInput) }, 150)

        keyboardButton.setImageResource(R.drawable.ic_mic)
        keyboardButton.imageTintList = tint(R.color.assistant_hint)
        keyboardButton.contentDescription = service.getString(R.string.assist_switch_to_voice)

        micButton.setImageResource(R.drawable.ic_paper_plane)
        micButton.contentDescription = service.getString(R.string.assist_send_message)
        setSquareButtonSize(micButton, 44)
    }

    private fun exitBottomKeyboardMode() {
        textInput.visibility = View.GONE
        inputSpacer.visibility = View.VISIBLE
        textInput.text?.clear()
        hideKeyboard(textInput)
        setOverlayFocusable(false)

        keyboardButton.setImageResource(R.drawable.ic_keyboard)
        keyboardButton.imageTintList = tint(R.color.assistant_hint)
        keyboardButton.contentDescription = service.getString(R.string.assist_toggle_keyboard)

        micButton.setImageResource(R.drawable.ic_mic)
        micButton.contentDescription = service.getString(R.string.assist_mic_button)
        setSquareButtonSize(micButton, 56)
    }

    private fun getTrimmedInput(editText: EditText): String =
        editText.text?.toString()?.trim().orEmpty()

    private fun setSquareButtonSize(button: View, sizeDp: Int) {
        val px = dp(sizeDp)
        button.layoutParams = button.layoutParams.apply {
            width = px
            height = px
        }
    }

    private fun showKeyboard(target: View) {
        inputMethodManager.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun hideKeyboard(target: View) {
        inputMethodManager.hideSoftInputFromWindow(target.windowToken, 0)
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun roundedBubble(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }

    private fun color(colorRes: Int) = ContextCompat.getColor(service, colorRes)

    private fun tint(colorRes: Int) =
        ColorStateList.valueOf(ContextCompat.getColor(service, colorRes))

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    // ─── Animations ──────────────────────────────────────────────────────────

    /** The currently visible primary surface (chat card by default; bottom panel if shown). */
    private fun activeSurface(): View =
        if (bottomPanel.visibility == View.VISIBLE) bottomPanel else responseCard

    /** Replays the entrance — used when the overlay is re-expanded after a collapse animation
     *  left the surface faded/offset. */
    fun replayEntrance() = playEntranceAnimation()

    /** Calm marvin entrance: scrim fades in, the card glides up and assembles with a light stagger. */
    private fun playEntranceAnimation() {
        micButton.scaleX = 1f
        micButton.scaleY = 1f

        val surface = activeSurface()
        gradientBg.alpha = 0f
        surface.alpha = 0f
        surface.translationY = dp(64).toFloat()

        rootContainer.post {
            gradientBg.animate().alpha(1f).setDuration(200L).start()
            surface.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320L)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
            staggerChildrenIn(surface)
        }
    }

    /** Subtle staggered fade/rise of the surface's visible children — restrained, never bouncy. */
    private fun staggerChildrenIn(surface: View) {
        if (surface !is android.view.ViewGroup) return
        var startDelay = 70L
        for (i in 0 until surface.childCount) {
            val child = surface.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.alpha = 0f
            child.translationY = dp(10).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            startDelay += 45L
        }
    }

    /** Graceful exit: the card slides down and fades with the scrim, then runs [action]. */
    fun animateOutThen(action: () -> Unit) {
        val surface = activeSurface()
        gradientBg.animate().alpha(0f).setDuration(220L).start()
        surface.animate()
            .alpha(0f)
            .translationY(dp(72).toFloat())
            .setDuration(220L)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction { runCatching { action() } }
            .start()
    }

    private fun startMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            micButton,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f, 1f)
        ).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun stopMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        micButton.scaleX = 1f
        micButton.scaleY = 1f
    }

    private fun createGradient(): ShapeDrawable {
        // Warm cream scrim (#F6F1EA, the home canvas) — soft dim that keeps the screen behind readable.
        val r = 246; val g = 241; val b = 234
        return ShapeDrawable(RectShape()).apply {
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader {
                    return LinearGradient(
                        0f, 0f, 0f, height.toFloat(),
                        intArrayOf(
                            Color.argb(0, r, g, b),
                            Color.argb(0, r, g, b),
                            Color.argb(120, r, g, b),
                            Color.argb(210, r, g, b)
                        ),
                        floatArrayOf(0f, 0.45f, 0.74f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
            }
        }
    }
}
