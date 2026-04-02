package com.example.mobile_assistant

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object ScreenPageScrollDispatcher {
    const val DEFAULT_SCROLL_DURATION_MS = 320L

    private const val GESTURE_SETTLE_DELAY_MS = 220L
    private const val GESTURE_TIMEOUT_MS = 2_000L

    fun scrollPageDownInForegroundApp(
        service: AssistantAccessibilityService,
        expectedPackage: String?,
        durationMs: Long = DEFAULT_SCROLL_DURATION_MS
    ): PageScrollResult {
        return scrollPageInForegroundApp(
            service = service,
            direction = PageScrollDirection.DOWN,
            expectedPackage = expectedPackage,
            durationMs = durationMs
        )
    }

    fun scrollPageInForegroundApp(
        service: AssistantAccessibilityService,
        direction: PageScrollDirection,
        expectedPackage: String?,
        durationMs: Long = DEFAULT_SCROLL_DURATION_MS
    ): PageScrollResult {
        val foregroundTarget = AssistantUiTargeting.findForegroundAppTarget(service)
            ?: return PageScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                durationMs = durationMs,
                error = "Could not resolve the current foreground app."
            )

        if (!expectedPackage.isNullOrBlank() && foregroundTarget.packageName != expectedPackage) {
            return PageScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                durationMs = durationMs,
                packageName = foregroundTarget.packageName,
                error = "Foreground app changed to ${foregroundTarget.packageName}. Read the screen again before using scroll_page."
            )
        }

        val width = foregroundTarget.boundsRight - foregroundTarget.boundsLeft
        val height = foregroundTarget.boundsBottom - foregroundTarget.boundsTop
        if (width <= 1 || height <= 1) {
            return PageScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                durationMs = durationMs,
                packageName = foregroundTarget.packageName,
                error = "Could not resolve safe page scroll bounds for the foreground app."
            )
        }

        val scrollPath = PageScrollMath.pathFor(
            bounds = ScreenBounds(
                width = width,
                height = height,
                left = foregroundTarget.boundsLeft,
                top = foregroundTarget.boundsTop
            ),
            direction = direction
        )

        val snapshot = service.detachAssistantVisualsForGesture()
        try {
            SystemClock.sleep(GESTURE_SETTLE_DELAY_MS)
            return dispatchScrollGesture(
                service = service,
                scrollPath = scrollPath,
                direction = direction,
                durationMs = durationMs,
                packageName = foregroundTarget.packageName
            )
        } finally {
            service.restoreAssistantVisualsAfterCapture(snapshot)
        }
    }

    private fun dispatchScrollGesture(
        service: AssistantAccessibilityService,
        scrollPath: PageScrollPath,
        direction: PageScrollDirection,
        durationMs: Long,
        packageName: String?
    ): PageScrollResult {
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val path = Path().apply {
            moveTo(scrollPath.start.x.toFloat(), scrollPath.start.y.toFloat())
            lineTo(scrollPath.end.x.toFloat(), scrollPath.end.y.toFloat())
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
                    callbackError = "Page scroll gesture was cancelled before it completed."
                    completionLatch.countDown()
                }
            },
            null
        )

        if (!dispatched) {
            return PageScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                durationMs = safeDurationMs,
                packageName = packageName,
                error = "Android rejected the page scroll gesture dispatch."
            )
        }

        if (!completionLatch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return PageScrollResult(
                scrolled = false,
                direction = direction.wireValue,
                durationMs = safeDurationMs,
                packageName = packageName,
                error = "Page scroll gesture timed out before Android confirmed completion."
            )
        }

        return PageScrollResult(
            scrolled = completed,
            direction = direction.wireValue,
            durationMs = safeDurationMs,
            packageName = packageName,
            error = callbackError
        )
    }
}
