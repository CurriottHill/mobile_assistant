package com.example.mobile_assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object ForegroundScreenshotter {

    fun capture(service: AssistantAccessibilityService): ScreenshotCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotCaptureResult(ok = false, error = "Screenshot capture requires Android 11 or later.")
        }

        val target = findForegroundAppTarget(service)
            ?: return ScreenshotCaptureResult(ok = false, error = "Could not resolve a foreground app window.")

        val snapshot = service.hideAssistantVisualsForCapture()
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<ScreenshotCaptureResult>()

        try {
            val windowId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) target.windowId else null
            val screenshotLeft = if (windowId != null) target.boundsLeft else 0
            val screenshotTop = if (windowId != null) target.boundsTop else 0
            if (windowId != null) {
                service.takeScreenshotOfWindow(
                    windowId,
                    executor,
                    screenshotCallback(service, target, screenshotLeft, screenshotTop, resultRef, latch)
                )
            } else {
                @Suppress("DEPRECATION")
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    screenshotCallback(service, target, screenshotLeft, screenshotTop, resultRef, latch)
                )
            }

            if (!latch.await(5, TimeUnit.SECONDS)) {
                return ScreenshotCaptureResult(ok = false, error = "Screenshot timed out.")
            }
            return resultRef.get() ?: ScreenshotCaptureResult(ok = false, error = "Screenshot failed.")
        } finally {
            service.restoreAssistantVisualsAfterCapture(snapshot)
            executor.shutdown()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun screenshotCallback(
        service: AssistantAccessibilityService,
        target: ForegroundAppTarget,
        screenshotLeft: Int,
        screenshotTop: Int,
        resultRef: AtomicReference<ScreenshotCaptureResult>,
        latch: CountDownLatch
    ): AccessibilityService.TakeScreenshotCallback {
        return object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                resultRef.set(saveScreenshot(service, target, screenshot, screenshotLeft, screenshotTop))
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                resultRef.set(
                    ScreenshotCaptureResult(
                        ok = false,
                        packageName = target.packageName,
                        error = "Screenshot failed with code $errorCode."
                    )
                )
                latch.countDown()
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun saveScreenshot(
        service: AssistantAccessibilityService,
        target: ForegroundAppTarget,
        screenshot: AccessibilityService.ScreenshotResult,
        screenshotLeft: Int,
        screenshotTop: Int
    ): ScreenshotCaptureResult {
        val hardwareBuffer = screenshot.hardwareBuffer
        val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
            ?: run {
                hardwareBuffer.close()
                return ScreenshotCaptureResult(
                    ok = false,
                    packageName = target.packageName,
                    error = "Could not decode screenshot buffer."
                )
            }

        val sourceWidth = wrapped.width
        val sourceHeight = wrapped.height
        val scaled = scaleBitmapIfNeeded(wrapped)
        val unAnnotated = if (scaled !== wrapped) {
            wrapped.recycle()
            scaled
        } else {
            wrapped.copy(Bitmap.Config.ARGB_8888, false)
        }
        hardwareBuffer.close()
        val bitmap = drawCoordinateGrid(unAnnotated)
        if (bitmap !== unAnnotated) unAnnotated.recycle()

        return runCatching {
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
            val file = File(service.cacheDir, "foreground-${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            ScreenshotCaptureResult(
                ok = true,
                packageName = target.packageName,
                filePath = file.absolutePath,
                dataUrl = "data:image/jpeg;base64,$base64",
                width = bitmap.width,
                height = bitmap.height,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                sourceLeft = screenshotLeft,
                sourceTop = screenshotTop
            )
        }.getOrElse { error ->
            ScreenshotCaptureResult(
                ok = false,
                packageName = target.packageName,
                error = error.message ?: "Failed to save screenshot."
            )
        }.also {
            bitmap.recycle()
        }
    }

    private fun drawCoordinateGrid(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val majorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 255, 255, 255)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val minorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(50, 255, 255, 255)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val labelSize = (w * 0.034f).coerceIn(11f, 18f)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = labelSize
            typeface = Typeface.MONOSPACE
            style = Paint.Style.FILL
        }
        val shadowPaint = Paint(labelPaint).apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = labelSize * 0.2f
        }

        // Minor grid: every 5% (unlabeled)
        for (i in 1 until 20) {
            if (i % 2 == 0) continue // skip majors, drawn below
            val frac = i / 20f
            canvas.drawLine(frac * w, 0f, frac * w, h.toFloat(), minorLinePaint)
            canvas.drawLine(0f, frac * h, w.toFloat(), frac * h, minorLinePaint)
        }

        // Major grid: every 10%, labeled
        val labelYTop = labelSize + 2f
        for (i in 1 until 10) {
            val frac = i / 10f
            val label = "%.1f".format(frac)

            val xPx = frac * w
            canvas.drawLine(xPx, 0f, xPx, h.toFloat(), majorLinePaint)
            canvas.drawText(label, xPx + 2f, labelYTop, shadowPaint)
            canvas.drawText(label, xPx + 2f, labelYTop, labelPaint)

            val yPx = frac * h
            canvas.drawLine(0f, yPx, w.toFloat(), yPx, majorLinePaint)
            canvas.drawText(label, 2f, yPx - 2f, shadowPaint)
            canvas.drawText(label, 2f, yPx - 2f, labelPaint)
        }

        // Edge labels so the model can interpolate into the 0.9–1.0 band
        canvas.drawText("0.0", 2f, labelSize, shadowPaint)
        canvas.drawText("0.0", 2f, labelSize, labelPaint)
        canvas.drawText("1.0", 2f, h - 2f, shadowPaint)
        canvas.drawText("1.0", 2f, h - 2f, labelPaint)

        return out
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDimension = 720
        val width = bitmap.width
        val height = bitmap.height
        val largest = maxOf(width, height)
        if (largest <= maxDimension) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val scale = maxDimension.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun findForegroundAppTarget(service: AssistantAccessibilityService): ForegroundAppTarget? {
        return AssistantUiTargeting.findForegroundAppTarget(service)
    }

}

internal data class ScreenshotCaptureResult(
    val ok: Boolean,
    val packageName: String? = null,
    val filePath: String? = null,
    val dataUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val sourceLeft: Int? = null,
    val sourceTop: Int? = null,
    val error: String? = null
)
