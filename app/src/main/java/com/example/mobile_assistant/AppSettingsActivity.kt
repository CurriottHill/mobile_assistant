package com.example.mobile_assistant

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AppSettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("marvin_prefs", MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var previewJob: Job? = null
    private var previewAudioTrack: AudioTrack? = null

    data class Voice(val slug: String, val name: String, val description: String)

    private val voices = listOf(
        Voice("aura-2-asteria-en",  "Asteria",  "Female · Clear, Confident, Energetic"),
        Voice("aura-2-luna-en",     "Luna",     "Female · Friendly, Natural, Engaging"),
        Voice("aura-2-aurora-en",   "Aurora",   "Female · Cheerful, Expressive, Energetic"),
        Voice("aura-2-thalia-en",   "Thalia",   "Female · Clear, Confident, Enthusiastic"),
        Voice("aura-2-harmonia-en", "Harmonia", "Female · Empathetic, Clear, Calm"),
        Voice("aura-2-athena-en",   "Athena",   "Female · Calm, Smooth, Professional"),
        Voice("aura-2-pandora-en",  "Pandora",  "Female · Smooth, Calm · British"),
        Voice("aura-2-theia-en",    "Theia",    "Female · Polite, Sincere · Australian"),
        Voice("aura-2-orion-en",    "Orion",    "Male · Approachable, Calm, Polite"),
        Voice("aura-2-apollo-en",   "Apollo",   "Male · Confident, Comfortable, Casual"),
        Voice("aura-2-hermes-en",   "Hermes",   "Male · Expressive, Engaging, Professional"),
        Voice("aura-2-zeus-en",     "Zeus",     "Male · Deep, Trustworthy, Smooth"),
        Voice("aura-2-jupiter-en",  "Jupiter",  "Male · Expressive, Knowledgeable · Baritone"),
        Voice("aura-2-draco-en",    "Draco",    "Male · Warm, Approachable · British Baritone"),
        Voice("aura-2-hyperion-en", "Hyperion", "Male · Caring, Warm, Empathetic · Australian"),
    )

    private lateinit var voiceListContainer: LinearLayout
    private val voiceRowViews = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        setupAiProviderToggle()
        setupSpeedSlider()
        voiceListContainer = findViewById(R.id.voiceListContainer)
        buildVoiceList()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewJob?.cancel()
        stopPreviewPlayback()
        scope.cancel()
    }

    // ─── AI Provider ─────────────────────────────────────────────────────────

    private fun setupAiProviderToggle() {
        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.toggleAiProvider)
        val savedProvider = prefs.getString("pref_ai_provider", "anthropic") ?: "anthropic"

        val primaryColor = ContextCompat.getColor(this, R.color.home_button_primary)
        val primaryTint = ColorStateList.valueOf(primaryColor)
        val strokeColor = ContextCompat.getColor(this, R.color.home_panel_stroke)
        val strokeTint = ColorStateList.valueOf(strokeColor)
        val titleColor = ContextCompat.getColor(this, R.color.home_panel_title)
        val titleTint = ColorStateList.valueOf(titleColor)
        val whiteTint = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.white))

        val btnAnthropic = findViewById<MaterialButton>(R.id.btnProviderAnthropic)
        val btnOpenAI = findViewById<MaterialButton>(R.id.btnProviderOpenAI)

        fun applyButtonState(btn: MaterialButton, selected: Boolean) {
            if (selected) {
                btn.backgroundTintList = primaryTint
                btn.strokeColor = primaryTint
                btn.setTextColor(whiteTint)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                btn.strokeColor = strokeTint
                btn.setTextColor(titleTint)
            }
        }

        applyButtonState(btnAnthropic, savedProvider == "anthropic")
        applyButtonState(btnOpenAI, savedProvider == "openai")

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val provider = if (checkedId == R.id.btnProviderAnthropic) "anthropic" else "openai"
            prefs.edit().putString("pref_ai_provider", provider).apply()
            applyButtonState(btnAnthropic, provider == "anthropic")
            applyButtonState(btnOpenAI, provider == "openai")
        }

        val initialId = if (savedProvider == "openai") R.id.btnProviderOpenAI else R.id.btnProviderAnthropic
        toggleGroup.check(initialId)
    }

    // ─── Speed slider ─────────────────────────────────────────────────────────

    private fun setupSpeedSlider() {
        val slider = findViewById<Slider>(R.id.speedSlider)
        val speedLabel = findViewById<TextView>(R.id.textSpeedValue)

        val savedSpeed = prefs.getFloat("pref_tts_speed", 1.0f)
        slider.value = savedSpeed.coerceIn(slider.valueFrom, slider.valueTo)
        speedLabel.text = formatSpeed(slider.value)

        slider.addOnChangeListener { _, value, _ ->
            speedLabel.text = formatSpeed(value)
        }

        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val speed = slider.value
                prefs.edit().putFloat("pref_tts_speed", speed).apply()
                val currentVoice = prefs.getString("pref_tts_voice", "aura-2-asteria-en") ?: "aura-2-asteria-en"
                playPreview(currentVoice, speed)
            }
        })
    }

    private fun formatSpeed(value: Float): String {
        return if (value == value.toLong().toFloat()) {
            "${value.toInt()}.0×"
        } else {
            "%.1f×".format(value)
        }
    }

    // ─── Voice list ──────────────────────────────────────────────────────────

    private fun buildVoiceList() {
        val currentVoice = prefs.getString("pref_tts_voice", "aura-2-asteria-en") ?: "aura-2-asteria-en"
        val strokeColor = ContextCompat.getColor(this, R.color.home_panel_stroke)

        voices.forEachIndexed { index, voice ->
            val row = createVoiceRow(voice, voice.slug == currentVoice)
            voiceRowViews[voice.slug] = row
            row.setOnClickListener {
                prefs.edit().putString("pref_tts_voice", voice.slug).apply()
                refreshVoiceSelection(voice.slug)
                val speed = prefs.getFloat("pref_tts_speed", 1.0f)
                playPreview(voice.slug, speed)
            }
            voiceListContainer.addView(row)

            if (index < voices.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).also { it.marginStart = dp(20) }
                    setBackgroundColor(strokeColor)
                }
                voiceListContainer.addView(divider)
            }
        }
    }

    private fun createVoiceRow(voice: Voice, selected: Boolean): LinearLayout {
        val titleColor = ContextCompat.getColor(this, R.color.home_panel_title)
        val bodyColor = ContextCompat.getColor(this, R.color.home_panel_body)
        val padding = dp(20)
        val paddingV = dp(16)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, paddingV, padding, paddingV)
            isClickable = true
            isFocusable = true
            background = android.util.TypedValue().let {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                ContextCompat.getDrawable(this@AppSettingsActivity, it.resourceId)
            }
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = voice.name
            textSize = 16f
            setTextColor(titleColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val descParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(3) }
        val descView = TextView(this).apply {
            text = voice.description
            textSize = 13f
            setTextColor(bodyColor)
            layoutParams = descParams
        }

        textColumn.addView(nameView)
        textColumn.addView(descView)

        val indicatorParams = LinearLayout.LayoutParams(dp(20), dp(20)).also {
            it.gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val indicator = View(this).apply {
            layoutParams = indicatorParams
            background = buildIndicatorDrawable(selected)
        }

        row.addView(textColumn)
        row.addView(indicator)
        row.tag = indicator

        return row
    }

    private fun refreshVoiceSelection(selectedSlug: String) {
        voiceRowViews.forEach { (slug, row) ->
            val indicator = row.tag as? View ?: return@forEach
            indicator.background = buildIndicatorDrawable(slug == selectedSlug)
        }
    }

    private fun buildIndicatorDrawable(selected: Boolean): GradientDrawable {
        val primaryColor = ContextCompat.getColor(this, R.color.home_button_primary)
        val strokeColor = ContextCompat.getColor(this, R.color.home_panel_stroke)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (selected) {
                setColor(primaryColor)
            } else {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(2), strokeColor)
            }
        }
    }

    // ─── TTS preview ─────────────────────────────────────────────────────────

    private fun playPreview(voiceSlug: String, speed: Float) {
        val apiKey = BuildConfig.DEEPGRAM_API_KEY.trim()
        if (apiKey.isBlank()) return

        previewJob?.cancel()
        stopPreviewPlayback()

        previewJob = scope.launch {
            runCatching { streamPreview(apiKey, voiceSlug, speed) }
        }
    }

    private suspend fun streamPreview(apiKey: String, voiceSlug: String, speed: Float) =
        withContext(Dispatchers.IO) {
            val speedParam = "%.2f".format(speed)
            val url = "https://api.deepgram.com/v1/speak" +
                    "?model=$voiceSlug&encoding=linear16&sample_rate=24000&container=none&speed=$speedParam"

            val requestBody = JSONObject()
                .put("text", "Hello, I am your voice assistant")
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Token $apiKey")
                .post(requestBody)
                .build()

            val sampleRate = 24000
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            previewAudioTrack = audioTrack
            audioTrack.play()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext
                    response.body?.byteStream()?.use { stream ->
                        val buffer = ByteArray(4096)
                        var n: Int
                        while (stream.read(buffer).also { n = it } != -1) {
                            audioTrack.write(buffer, 0, n)
                        }
                    }
                }
            } finally {
                runCatching { audioTrack.stop(); audioTrack.release() }
                if (previewAudioTrack === audioTrack) previewAudioTrack = null
            }
        }

    private fun stopPreviewPlayback() {
        val track = previewAudioTrack
        previewAudioTrack = null
        if (track != null) {
            runCatching { track.pause(); track.flush(); track.release() }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
