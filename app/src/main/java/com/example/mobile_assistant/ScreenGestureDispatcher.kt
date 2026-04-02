package com.example.mobile_assistant

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object ScreenGestureDispatcher {
    const val DEFAULT_TAP_DURATION_MS = 120L

    private const val GESTURE_SETTLE_DELAY_MS = 220L
    private const val GESTURE_TIMEOUT_MS = 2_000L

    fun tapFullScreenPoint(
        service: AssistantAccessibilityService,
        point: ScreenPixelPoint,
        expectedPackage: String?,
        durationMs: Long = DEFAULT_TAP_DURATION_MS
    ): CoordinateTapResult {
        val foregroundTarget = AssistantUiTargeting.findForegroundAppTarget(service)
            ?: return CoordinateTapResult(
                tapped = false,
                xPx = point.x,
                yPx = point.y,
                durationMs = durationMs,
                error = "Could not resolve the current foreground app."
            )

        if (!expectedPackage.isNullOrBlank() && foregroundTarget.packageName != expectedPackage) {
            return CoordinateTapResult(
                tapped = false,
                xPx = point.x,
                yPx = point.y,
                durationMs = durationMs,
                packageName = foregroundTarget.packageName,
                error = "Foreground app changed to ${foregroundTarget.packageName}. Read the screen again before using tap_xy."
            )
        }

        val snapshot = service.detachAssistantVisualsForGesture()
        try {
            SystemClock.sleep(GESTURE_SETTLE_DELAY_MS)
            return dispatchTapGesture(
                service = service,
                point = point,
                durationMs = durationMs,
                packageName = foregroundTarget.packageName
            )
        } finally {
            service.restoreAssistantVisualsAfterCapture(snapshot)
        }
    }

    private fun dispatchTapGesture(
        service: AssistantAccessibilityService,
        point: ScreenPixelPoint,
        durationMs: Long,
        packageName: String?
    ): CoordinateTapResult {
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, safeDurationMs))
            .build()

        val completionLatch = CountDownLatch(1)
        var completed = false
        var callbackError: String? = null

        val dispatched = service.dispatchGesture(
            gesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    completionLatch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callbackError = "Tap gesture was cancelled before it completed."
                    completionLatch.countDown()
                }
            },
            null
        )

        if (!dispatched) {
            return CoordinateTapResult(
                tapped = false,
                xPx = point.x,
                yPx = point.y,
                durationMs = safeDurationMs,
                packageName = packageName,
                error = "Android rejected the tap gesture dispatch."
            )
        }

        if (!completionLatch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return CoordinateTapResult(
                tapped = false,
                xPx = point.x,
                yPx = point.y,
                durationMs = safeDurationMs,
                packageName = packageName,
                error = "Tap gesture timed out before Android confirmed completion."
            )
        }

        return CoordinateTapResult(
            tapped = completed,
            xPx = point.x,
            yPx = point.y,
            durationMs = safeDurationMs,
            packageName = packageName,
            error = callbackError
        )
    }
}
