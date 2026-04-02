package com.example.mobile_assistant

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal object AssistantUiTargeting {
    val IGNORED_PACKAGES = setOf(
        "com.example.mobile_assistant",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.swiftkey.swiftkey",
        "com.touchtype.swiftkey",
        "com.baidu.input",
        "com.sec.android.inputmethod",
        "com.google.android.apps.inputmethod.hindi",
        "com.google.android.apps.inputmethod.zhuyin"
    )

    fun isIgnoredPackage(packageName: String?): Boolean {
        return packageName != null && IGNORED_PACKAGES.contains(packageName)
    }

    fun currentUnderlyingPackage(service: AssistantAccessibilityService): String? {
        val root = service.getUnderlyingAppRoot() ?: return null
        return try {
            root.packageName?.toString()?.trim()?.ifBlank { null }
        } finally {
            root.recycle()
        }
    }

    fun findAppRoot(service: AssistantAccessibilityService): AccessibilityNodeInfo? {
        val windows = service.windows
        if (windows.isNullOrEmpty()) {
            return service.rootInActiveWindow?.takeIf {
                !isIgnoredPackage(it.packageName?.toString())
            }
        }

        for (window in candidateApplicationWindows(windows)) {
            val windowRoot = window.root ?: continue
            val windowPkg = windowRoot.packageName?.toString()
            if (windowPkg == null || isIgnoredPackage(windowPkg)) {
                windowRoot.recycle()
                continue
            }
            return windowRoot
        }

        return service.rootInActiveWindow?.takeIf {
            !isIgnoredPackage(it.packageName?.toString())
        }
    }

    fun findForegroundAppTarget(service: AssistantAccessibilityService): ForegroundAppTarget? {
        val windows = service.windows.orEmpty()
        for (window in candidateApplicationWindows(windows)) {
            val root = window.root ?: continue
            val packageName = root.packageName?.toString()
            root.recycle()
            if (packageName.isNullOrBlank() || isIgnoredPackage(packageName)) continue
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            return ForegroundAppTarget(
                windowId = window.id,
                packageName = packageName,
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom
            )
        }
        return null
    }

    private fun candidateApplicationWindows(
        windows: List<AccessibilityWindowInfo>
    ): List<AccessibilityWindowInfo> {
        return (
            windows.filter { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.isActive &&
                    !window.isInPictureInPictureCompat()
            } + windows.filter { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.isFocused &&
                    !window.isInPictureInPictureCompat()
            } + windows.filter { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    !window.isInPictureInPictureCompat()
            }
            ).distinctBy { it.id }
    }

    private fun AccessibilityWindowInfo.isInPictureInPictureCompat(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
    }
}

internal data class ForegroundAppTarget(
    val windowId: Int?,
    val packageName: String,
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0
)
