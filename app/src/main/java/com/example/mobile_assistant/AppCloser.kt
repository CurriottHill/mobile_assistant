package com.example.mobile_assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

internal object AppCloser {
    private const val RECENTS_OPEN_DELAY_MS = 650L
    private const val DISMISS_SETTLE_DELAY_MS = 450L

    fun closeCurrentForegroundApp(service: AssistantAccessibilityService): PressHomeResult {
        val targetPackage = currentPackage(service)
        val targetLabel = targetPackage?.let { appLabel(service, it) }
        val recentsOpened = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        if (!recentsOpened) {
            return PressHomeResult(
                pressed = false,
                error = "Could not open recent apps to close the current app."
            )
        }

        SystemClock.sleep(RECENTS_OPEN_DELAY_MS)

        if (dismissViaAccessibilityAction(service, targetLabel)) {
            SystemClock.sleep(DISMISS_SETTLE_DELAY_MS)
            return PressHomeResult(pressed = true)
        }

        val swipeResult = dismissViaSwipe(service, targetLabel)
        if (swipeResult.swiped) {
            SystemClock.sleep(DISMISS_SETTLE_DELAY_MS)
            return PressHomeResult(pressed = true)
        }

        return PressHomeResult(
            pressed = false,
            error = listOfNotNull(
                "Could not dismiss the current app from recent apps.",
                swipeResult.error
            ).joinToString(" ")
        )
    }

    private fun currentPackage(service: AssistantAccessibilityService): String? {
        val root = service.getUnderlyingAppRoot() ?: return null
        return try {
            root.packageName?.toString()?.trim()?.ifBlank { null }
        } finally {
            root.recycle()
        }
    }

    private fun appLabel(service: AssistantAccessibilityService, packageName: String): String? {
        return runCatching {
            val info = service.packageManager.getApplicationInfo(packageName, 0)
            service.packageManager.getApplicationLabel(info).toString().trim().ifBlank { null }
        }.getOrNull()
    }

    private fun dismissViaAccessibilityAction(
        service: AssistantAccessibilityService,
        targetLabel: String?
    ): Boolean {
        val root = service.getUnderlyingAppRoot() ?: return false
        val candidates = mutableListOf<NodeCandidate>()
        return try {
            val rootBounds = boundsOf(root)
            collectCandidates(root, rootBounds, targetLabel, candidates, requireDismissAction = true)
            candidates
                .maxByOrNull { it.score }
                ?.node
                ?.performAction(AccessibilityNodeInfo.ACTION_DISMISS) == true
        } finally {
            candidates.forEach { it.node.recycle() }
            root.recycle()
        }
    }

    private fun dismissViaSwipe(
        service: AssistantAccessibilityService,
        targetLabel: String?
    ): SwipeResult {
        val root = service.getUnderlyingAppRoot()
            ?: return SwipeResult(swiped = false, direction = "up", error = "Recent apps screen is unavailable.")
        val candidates = mutableListOf<NodeCandidate>()
        return try {
            val rootBounds = boundsOf(root)
            collectCandidates(root, rootBounds, targetLabel, candidates, requireDismissAction = false)
            val card = candidates.maxByOrNull { it.score }?.bounds
                ?: rootBounds.takeIf { it.width() > 0 && it.height() > 0 }
                ?: return SwipeResult(swiped = false, direction = "up", error = "Could not locate a recent app card to dismiss.")

            val centerX = card.centerX().coerceIn(rootBounds.left + 1, rootBounds.right - 1)
            val startY = card.centerY().coerceIn(rootBounds.top + 1, rootBounds.bottom - 1)
            val endY = (card.top - card.height() / 2).coerceAtLeast(rootBounds.top + 24)
            ScreenSwipeDispatcher.swipeFullScreenPath(
                service = service,
                swipePath = SwipePath(
                    start = ScreenPixelPoint(centerX, startY),
                    end = ScreenPixelPoint(centerX, endY)
                ),
                direction = SwipeDirection.UP,
                packageName = root.packageName?.toString()
            )
        } finally {
            candidates.forEach { it.node.recycle() }
            root.recycle()
        }
    }

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        rootBounds: Rect?,
        targetLabel: String?,
        out: MutableList<NodeCandidate>,
        requireDismissAction: Boolean
    ) {
        val bounds = boundsOf(node)
        val hasBounds = bounds.width() > 0 && bounds.height() > 0
        val hasDismiss = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
        if (hasBounds && (!requireDismissAction || hasDismiss) && looksLikeTaskCard(bounds, rootBounds)) {
            val labelText = subtreeText(node, depth = 3).lowercase()
            out += NodeCandidate(
                node = AccessibilityNodeInfo.obtain(node),
                bounds = Rect(bounds),
                score = scoreCandidate(bounds, rootBounds, labelText, targetLabel)
            )
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectCandidates(child, rootBounds, targetLabel, out, requireDismissAction)
            } finally {
                child.recycle()
            }
        }
    }

    private fun looksLikeTaskCard(bounds: Rect, rootBounds: Rect?): Boolean {
        val root = rootBounds ?: return true
        if (bounds == root) return false
        val rootArea = root.width().coerceAtLeast(1) * root.height().coerceAtLeast(1)
        val area = bounds.width() * bounds.height()
        return area > rootArea * 0.08 && area < rootArea * 0.98
    }

    private fun scoreCandidate(
        bounds: Rect,
        rootBounds: Rect?,
        labelText: String,
        targetLabel: String?
    ): Int {
        val areaScore = (bounds.width() * bounds.height()) / 1_000
        val labelScore = if (!targetLabel.isNullOrBlank() && labelText.contains(targetLabel.lowercase())) 20_000 else 0
        val centerPenalty = rootBounds?.let { root ->
            abs(bounds.centerX() - root.centerX()) / 8 + abs(bounds.centerY() - root.centerY()) / 12
        } ?: 0
        return labelScore + areaScore - centerPenalty
    }

    private fun subtreeText(node: AccessibilityNodeInfo, depth: Int): String {
        val parts = mutableListOf<String>()
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(parts::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(parts::add)
        if (depth <= 0) return parts.joinToString(" ")

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                subtreeText(child, depth - 1).takeIf { it.isNotBlank() }?.let(parts::add)
            } finally {
                child.recycle()
            }
        }
        return parts.joinToString(" ")
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    private data class NodeCandidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val score: Int
    )
}
