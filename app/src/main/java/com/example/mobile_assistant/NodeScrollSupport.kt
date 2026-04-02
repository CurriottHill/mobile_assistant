package com.example.mobile_assistant

import android.view.accessibility.AccessibilityNodeInfo

internal enum class NodeScrollDirection(
    val wireValue: String,
    val accessibilityAction: Int
) {
    UP(
        wireValue = "up",
        accessibilityAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    ),
    DOWN(
        wireValue = "down",
        accessibilityAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    );

    companion object {
        fun fromRaw(raw: String?): NodeScrollDirection? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

internal data class NodeScrollResult(
    val scrolled: Boolean,
    val direction: String,
    val label: String? = null,
    val matchedNodeRef: String? = null,
    val error: String? = null
)
