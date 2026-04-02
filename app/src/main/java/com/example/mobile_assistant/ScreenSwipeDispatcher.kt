package com.example.mobile_assistant

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object ScreenSwipeDispatcher {
    const val DEFAULT_SWIPE_DURATION_MS = 220L

    private const val GESTURE_SETTLE_DELAY_MS = 220L
    private const val GESTURE_TIMEOUT_MS = 2_000L

    fun swipeInForegroundApp(
        service: AssistantAccessibilityService,
        direction: SwipeDirection,
        expectedPackage: String?,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS
    ): SwipeResult {
        val foregroundTarget = AssistantUiTargeting.findForegroundAppTarget(service)
            ?: return SwipeResult(
                swiped = false,
                direction = direction.wireValue,
                error = "Could not resolve the current foreground app."
            )

        if (!expectedPackage.isNullOrBlank() && foregroundTarget.packageName != expectedPackage) {
            return SwipeResult(
                swiped = false,
                direction = direction.wireValue,
                packageName = foregroundTarget.packageName,
                error = "Foreground app changed to ${foregroundTarget.packageName}. Read the screen again before swiping."
            )
        }

        val width = foregroundTarget.boundsRight - foregroundTarget.boundsLeft
        val height = foregroundTarget.boundsBottom - foregroundTarget.boundsTop
        if (width <= 1 || height <= 1) {
            return SwipeResult(
                swiped = false,
                direction = direction.wireValue,
                packageName = foregroundTarget.packageName,
                error = "Could not resolve safe swipe bounds for the foreground app."
            )
        }

        val swipePath = GallerySwipeMath.pathFor(
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
            return dispatchSwipeGesture(
                service = service,
                swipePath = swipePath,
                direction = direction,
                durationMs = durationMs,
                packageName = foregroundTarget.packageName
            )
        } finally {
            service.restoreAssistantVisualsAfterCapture(snapshot)
        }
    }

    private fun dispatchSwipeGesture(
        service: AssistantAccessibilityService,
        swipePath: SwipePath,
        direction: SwipeDirection,
        durationMs: Long,
        packageName: String?
    ): SwipeResult {
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val path = Path().apply {
            moveTo(swipePath.start.x.toFloat(), swipePath.start.y.toFloat())
            lineTo(swipePath.end.x.toFloat(), swipePath.end.y.toFloat())
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
                    callbackError = "Swipe gesture was cancelled before it completed."
                    completionLatch.countDown()
                }
            },
            null
        )

        if (!dispatched) {
            return SwipeResult(
                swiped = false,
                direction = direction.wireValue,
                durationMs = safeDurationMs,
                packageName = packageName,
                error = "Android rejected the swipe gesture dispatch."
            )
        }

        if (!completionLatch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return SwipeResult(
                swiped = false,
                direction = direction.wireValue,
                durationMs = safeDurationMs,
                packageName = packageName,
                error = "Swipe gesture timed out before Android confirmed completion."
            )
        }

        return SwipeResult(
            swiped = completed,
            direction = direction.wireValue,
            durationMs = safeDurationMs,
            packageName = packageName,
            error = callbackError
        )
    }
}

internal data class SwipeResult(
    val swiped: Boolean,
    val direction: String,
    val durationMs: Long? = null,
    val packageName: String? = null,
    val error: String? = null
)
