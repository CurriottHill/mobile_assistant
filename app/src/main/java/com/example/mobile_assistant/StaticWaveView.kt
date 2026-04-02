package com.example.mobile_assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.sin
import kotlin.random.Random

class StaticWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 80
    private val heights = FloatArray(barCount)
    private val targetHeights = FloatArray(barCount)
    private val phases = FloatArray(barCount)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val accentColor = ContextCompat.getColor(context, R.color.assistant_wave_accent)
    private val accentDim = ContextCompat.getColor(context, R.color.assistant_wave_dim)

    private var time = 0.0
    private var amplitude = 0f
    private var targetAmplitude = 0f
    private var isActive = false
    private var lastFrameTime = 0L

    init {
        for (i in phases.indices) {
            phases[i] = Random.nextFloat() * (Math.PI * 2).toFloat()
        }
    }

    fun start() {
        isActive = true
        targetAmplitude = 0.6f
        lastFrameTime = System.nanoTime()
        postInvalidateOnAnimation()
    }

    fun stop() {
        isActive = false
        targetAmplitude = 0f
    }

    fun updateAmplitude(amplitude: Float) {
        if (!isActive) return
        // amplitude: 0.0 (silence) to 1.0 (max), set directly
        targetAmplitude = amplitude.coerceIn(0f, 1f)
    }

    private fun updateState(dt: Float) {
        time += dt * 2.2

        // Smooth amplitude interpolation
        amplitude += (targetAmplitude - amplitude) * 0.08f

        for (i in 0 until barCount) {
            val pos = i.toFloat() / barCount

            // Multiple sine waves at different frequencies for organic shape
            val wave1 = sin(pos * 8.0 + time * 1.8 + phases[i]).toFloat()
            val wave2 = sin(pos * 14.0 + time * 3.1 + phases[i] * 1.3).toFloat()
            val wave3 = sin(pos * 3.5 + time * 0.7).toFloat()

            // Random noise/static component
            val noise = (Random.nextFloat() - 0.5f) * 0.4f

            // Combine: waves + noise + base height
            val combined = (wave1 * 0.35f + wave2 * 0.2f + wave3 * 0.15f + noise * 0.3f + 0.5f)
            targetHeights[i] = (combined * amplitude).coerceIn(0f, 1f)

            // Per-bar flicker (static effect)
            if (isActive && Random.nextFloat() < 0.15f) {
                targetHeights[i] *= Random.nextFloat() * 0.5f + 0.5f
            }

            // Smooth interpolation toward target
            heights[i] += (targetHeights[i] - heights[i]) * 0.18f

            // Clamp tiny values to zero
            if (heights[i] < 0.005f) heights[i] = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameTime == 0L) 0.016f else ((now - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrameTime = now

        updateState(dt)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val spacing = w / barCount
        val barWidth = spacing * 0.38f

        barPaint.strokeCap = Paint.Cap.ROUND
        glowPaint.strokeCap = Paint.Cap.ROUND

        for (i in 0 until barCount) {
            val barH = heights[i] * h * 0.55f
            if (barH < 1.5f) continue

            val x = spacing * i + spacing * 0.5f
            val alpha = (heights[i] * 210f + 40f).toInt().coerceIn(0, 255)

            // Glow layer: wider, dimmer, creates soft glow
            glowPaint.strokeWidth = barWidth + 5f
            glowPaint.color = accentColor
            glowPaint.alpha = (alpha * 0.18f).toInt()
            canvas.drawLine(x, h, x, h - barH, glowPaint)

            // Main bar with gradient effect (brighter at tip)
            barPaint.strokeWidth = barWidth
            barPaint.shader = LinearGradient(
                x, h, x, h - barH,
                accentDim, accentColor,
                Shader.TileMode.CLAMP
            )
            barPaint.alpha = alpha
            canvas.drawLine(x, h, x, h - barH, barPaint)
        }

        // Reset shader
        barPaint.shader = null

        // Keep animating if active or bars haven't settled
        if (isActive || heights.any { it > 0.005f }) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isActive = false
    }
}
