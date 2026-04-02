package com.example.mobile_assistant

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlin.math.hypot

/**
 * Hosts the assistant overlay using TYPE_ACCESSIBILITY_OVERLAY so the
 * underlying app stays the active window and getRootInActiveWindow()
 * returns the target app's view tree, not our overlay.
 */
class AssistantAccessibilityService : AccessibilityService() {

    internal data class VisualStateSnapshot(
        val overlayVisibility: Int?,
        val bubbleVisibility: Int?,
        val dismissZoneVisibility: Int?,
        val overlayFlags: Int?,
        val bubbleFlags: Int?,
        val overlayWasAttached: Boolean,
        val bubbleWasAttached: Boolean
    )

    companion object {
        private const val ACTION_SHOW_OVERLAY = "com.example.mobile_assistant.SHOW_OVERLAY"
        private const val ACTION_HIDE_OVERLAY = "com.example.mobile_assistant.HIDE_OVERLAY"

        private const val BUBBLE_SIZE_DP = 56
        private const val DISMISS_ZONE_SIZE_DP = 64
        private const val DISMISS_SNAP_DISTANCE_DP = 80

        private val UI_SIGNAL_IGNORED_PACKAGES = AssistantUiTargeting.IGNORED_PACKAGES

        @Volatile
        var instance: AssistantAccessibilityService? = null
            private set
    }

    private var overlayController: AssistantOverlayController? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var dismissZoneView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubblePulseAnimator: ValueAnimator? = null
    private var isCollapsed = false
    private var lastBubbleX: Int? = null
    private var lastBubbleY: Int? = null
    private val uiSignalLock = Any()
    private var uiRevision = 0L
    private var lastUiPackage: String? = null
    private var lastUiEventUptimeMs = 0L

    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!shouldTrackUiEvent(event)) return

        val eventPackage = event.packageName?.toString()?.trim()?.ifBlank { null }
        if (eventPackage != null && eventPackage in UI_SIGNAL_IGNORED_PACKAGES) {
            return
        }

        synchronized(uiSignalLock) {
            uiRevision += 1
            if (!eventPackage.isNullOrBlank()) {
                lastUiPackage = eventPackage
            }
            lastUiEventUptimeMs = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_HIDE_OVERLAY -> hideOverlay()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        hideOverlay()
        removeBubble()
        instance = null
        super.onDestroy()
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun showOverlay() {
        if (overlayController != null) {
            if (isCollapsed) {
                expandOverlay()
            }
            return
        }
        removeBubble()
        isCollapsed = false
        val inflater = LayoutInflater.from(this)
        val overlayView = inflater.inflate(R.layout.activity_assistant_popup, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlayView, params)
        overlayParams = params

        overlayController = AssistantOverlayController(
            service = this,
            rootView = overlayView,
            windowManager = windowManager,
            onDismiss = { hideOverlay() },
            onCollapse = { collapseOverlay() }
        )
    }

    fun hideOverlay() {
        val controller = overlayController ?: return
        controller.destroy()
        runCatching { windowManager.removeView(controller.rootView) }
        overlayController = null
        overlayParams = null
        removeBubble()
        isCollapsed = false
    }

    fun getUnderlyingAppRoot(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    internal fun currentUiSignal(): UiSignal {
        val rootPackage = rootInActiveWindow?.let { root ->
            try {
                root.packageName?.toString()?.trim()?.ifBlank { null }
            } finally {
                root.recycle()
            }
        }

        synchronized(uiSignalLock) {
            val foregroundPackage = rootPackage
                ?.takeUnless { it in UI_SIGNAL_IGNORED_PACKAGES }
                ?: lastUiPackage

            return UiSignal(
                revision = uiRevision,
                foregroundPackage = foregroundPackage,
                lastEventUptimeMs = if (lastUiEventUptimeMs > 0L) lastUiEventUptimeMs else SystemClock.uptimeMillis()
            )
        }
    }

    internal fun hideAssistantVisualsForCapture(): VisualStateSnapshot {
        return hideAssistantVisuals(detachWindows = false)
    }

    internal fun detachAssistantVisualsForGesture(): VisualStateSnapshot {
        return hideAssistantVisuals(detachWindows = true)
    }

    private fun hideAssistantVisuals(detachWindows: Boolean): VisualStateSnapshot {
        val overlayVisibility = overlayController?.rootView?.visibility
        val bubbleVisibility = bubbleView?.visibility
        val dismissZoneVisibility = dismissZoneView?.visibility
        val overlayFlags = overlayParams?.flags
        val bubbleFlags = bubbleParams?.flags
        val overlayWasAttached = overlayController?.rootView?.isAttachedToWindow == true
        val bubbleWasAttached = bubbleView?.isAttachedToWindow == true

        // GONE matches the user-collapsed state that does not obstruct gestures.
        overlayController?.rootView?.visibility = View.GONE
        bubbleView?.visibility = View.GONE
        dismissZoneView?.visibility = View.GONE
        refreshWindowLayout(overlayController?.rootView, overlayParams)
        refreshWindowLayout(bubbleView, bubbleParams)
        updateWindowTouchability(
            view = overlayController?.rootView,
            params = overlayParams,
            touchable = false
        )
        updateWindowTouchability(
            view = bubbleView,
            params = bubbleParams,
            touchable = false
        )

        if (detachWindows) {
            detachWindow(overlayController?.rootView)
            detachWindow(bubbleView)
        }

        return VisualStateSnapshot(
            overlayVisibility = overlayVisibility,
            bubbleVisibility = bubbleVisibility,
            dismissZoneVisibility = dismissZoneVisibility,
            overlayFlags = overlayFlags,
            bubbleFlags = bubbleFlags,
            overlayWasAttached = overlayWasAttached,
            bubbleWasAttached = bubbleWasAttached
        )
    }

    internal fun restoreAssistantVisualsAfterCapture(snapshot: VisualStateSnapshot) {
        restoreWindowAttachment(
            view = overlayController?.rootView,
            params = overlayParams,
            shouldBeAttached = snapshot.overlayWasAttached
        )
        restoreWindowAttachment(
            view = bubbleView,
            params = bubbleParams,
            shouldBeAttached = snapshot.bubbleWasAttached
        )
        restoreWindowFlags(
            view = overlayController?.rootView,
            params = overlayParams,
            originalFlags = snapshot.overlayFlags
        )
        restoreWindowFlags(
            view = bubbleView,
            params = bubbleParams,
            originalFlags = snapshot.bubbleFlags
        )
        snapshot.overlayVisibility?.let { overlayController?.rootView?.visibility = it }
        snapshot.bubbleVisibility?.let { bubbleView?.visibility = it }
        snapshot.dismissZoneVisibility?.let { dismissZoneView?.visibility = it }
        refreshWindowLayout(overlayController?.rootView, overlayParams)
        refreshWindowLayout(bubbleView, bubbleParams)
    }

    private fun updateWindowTouchability(
        view: View?,
        params: WindowManager.LayoutParams?,
        touchable: Boolean
    ) {
        if (view == null || params == null) return
        val desiredFlags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (params.flags == desiredFlags) return
        params.flags = desiredFlags
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun restoreWindowFlags(
        view: View?,
        params: WindowManager.LayoutParams?,
        originalFlags: Int?
    ) {
        if (view == null || params == null || originalFlags == null || params.flags == originalFlags) return
        params.flags = originalFlags
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun refreshWindowLayout(
        view: View?,
        params: WindowManager.LayoutParams?
    ) {
        if (view == null || params == null) return
        view.requestLayout()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun detachWindow(view: View?) {
        if (view == null || !view.isAttachedToWindow) return
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun restoreWindowAttachment(
        view: View?,
        params: WindowManager.LayoutParams?,
        shouldBeAttached: Boolean
    ) {
        if (!shouldBeAttached || view == null || params == null || view.isAttachedToWindow) return
        runCatching { windowManager.addView(view, params) }
    }

    // ─── Collapse / Expand ───────────────────────────────────────────────────

    private fun collapseOverlay() {
        val controller = overlayController ?: return
        controller.rootView.visibility = View.GONE
        isCollapsed = true
        showBubble()
    }

    private fun expandOverlay() {
        removeBubble()
        isCollapsed = false

        val controller = overlayController
        if (controller != null) {
            controller.rootView.visibility = View.VISIBLE
        } else {
            showOverlay()
        }
    }

    // ─── Draggable Bubble ────────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun shouldTrackUiEvent(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> true
            else -> false
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val sizePx = dp(BUBBLE_SIZE_DP)

        val bubble = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@AssistantAccessibilityService, R.color.assistant_fab_bg))
                setStroke(dp(2), ContextCompat.getColor(this@AssistantAccessibilityService, R.color.assistant_fab_stroke))
            }
            background = bg
            elevation = dp(8).toFloat()

            val dot = View(context).apply {
                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@AssistantAccessibilityService, R.color.assistant_fab_dot))
                }
                background = dotBg
            }
            val dotSize = dp(12)
            val dotParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.CENTER
            }
            addView(dot, dotParams)
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastBubbleX ?: (screenWidth - sizePx - dp(16))
            y = lastBubbleY ?: (screenHeight / 3)
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params

        bubble.scaleX = 0f
        bubble.scaleY = 0f
        bubble.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.5f))
            .withEndAction { startBubblePulse(bubble) }
            .start()

        setupBubbleTouchListener(bubble, params, screenWidth, screenHeight)
    }

    private fun setupBubbleTouchListener(
        bubble: View,
        params: WindowManager.LayoutParams,
        screenWidth: Int,
        screenHeight: Int
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = dp(8)

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (!isDragging && hypot(dx, dy) > touchSlop) {
                        isDragging = true
                        showDismissZone()
                    }

                    if (isDragging) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(bubble, params) }
                        updateDismissZoneHighlight(params, screenWidth, screenHeight)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        hideDismissZone()
                        if (isOverDismissZone(params, screenWidth, screenHeight)) {
                            lastBubbleX = null
                            lastBubbleY = null
                            hideOverlay()
                        } else {
                            snapToEdge(params, bubble, screenWidth)
                            lastBubbleX = params.x
                            lastBubbleY = params.y
                        }
                    } else {
                        lastBubbleX = params.x
                        lastBubbleY = params.y
                        expandOverlay()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    hideDismissZone()
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge(
        params: WindowManager.LayoutParams,
        bubble: View,
        screenWidth: Int
    ) {
        val sizePx = dp(BUBBLE_SIZE_DP)
        val margin = dp(8)
        val centerX = params.x + sizePx / 2
        val targetX = if (centerX < screenWidth / 2) margin else screenWidth - sizePx - margin

        val animator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 250
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                runCatching { windowManager.updateViewLayout(bubble, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lastBubbleX = params.x
                    lastBubbleY = params.y
                }
            })
        }
        animator.start()
    }

    // ─── Dismiss Zone ────────────────────────────────────────────────────────

    private fun showDismissZone() {
        if (dismissZoneView != null) return

        val sizePx = dp(DISMISS_ZONE_SIZE_DP)
        val screenWidth = resources.displayMetrics.widthPixels

        val zone = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FF4444"))
                setStroke(dp(2), Color.parseColor("#66FF4444"))
            }
            background = bg

            val xIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B6B"))
            }
            val iconSize = dp(24)
            val iconParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            addView(xIcon, iconParams)
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 2 - sizePx / 2
            y = resources.displayMetrics.heightPixels - sizePx - dp(80)
        }

        windowManager.addView(zone, params)
        dismissZoneView = zone

        zone.alpha = 0f
        zone.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideDismissZone() {
        val zone = dismissZoneView ?: return
        zone.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                runCatching { windowManager.removeView(zone) }
                if (dismissZoneView === zone) dismissZoneView = null
            }
            .start()
    }

    private fun isOverDismissZone(
        bubbleParams: WindowManager.LayoutParams,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val bubbleSizePx = dp(BUBBLE_SIZE_DP)
        val dismissSizePx = dp(DISMISS_ZONE_SIZE_DP)
        val snapDist = dp(DISMISS_SNAP_DISTANCE_DP)

        val bubbleCenterX = bubbleParams.x + bubbleSizePx / 2f
        val bubbleCenterY = bubbleParams.y + bubbleSizePx / 2f

        val zoneCenterX = screenWidth / 2f
        val zoneCenterY = screenHeight - dismissSizePx / 2f - dp(80).toFloat()

        return hypot(
            (bubbleCenterX - zoneCenterX).toDouble(),
            (bubbleCenterY - zoneCenterY).toDouble()
        ) < snapDist
    }

    private fun updateDismissZoneHighlight(
        bubbleParams: WindowManager.LayoutParams,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val zone = dismissZoneView ?: return
        val isOver = isOverDismissZone(bubbleParams, screenWidth, screenHeight)
        val targetScale = if (isOver) 1.3f else 1f
        zone.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(150)
            .start()

        val bg = zone.background as? GradientDrawable
        if (isOver) {
            bg?.setColor(Color.parseColor("#66FF4444"))
            bg?.setStroke(dp(2), Color.parseColor("#AAFF4444"))
        } else {
            bg?.setColor(Color.parseColor("#33FF4444"))
            bg?.setStroke(dp(2), Color.parseColor("#66FF4444"))
        }
    }

    private fun removeBubble() {
        stopBubblePulse()
        bubbleView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        bubbleView = null
        bubbleParams = null

        dismissZoneView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        dismissZoneView = null
    }

    private fun startBubblePulse(bubble: View) {
        stopBubblePulse()
        bubblePulseAnimator = ValueAnimator.ofFloat(1f, 1.08f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                bubble.scaleX = scale
                bubble.scaleY = scale
            }
            start()
        }
    }

    private fun stopBubblePulse() {
        bubblePulseAnimator?.cancel()
        bubblePulseAnimator = null
    }
}
