package com.example.mobile_assistant

import kotlin.math.roundToInt

internal enum class SwipeDirection(val wireValue: String) {
    LEFT("left"),
    RIGHT("right"),
    UP("up"),
    DOWN("down");

    companion object {
        fun fromRaw(raw: String?): SwipeDirection? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

internal data class SwipePath(
    val start: ScreenPixelPoint,
    val end: ScreenPixelPoint
)

internal object GallerySwipeMath {
    private const val EDGE_INSET_FRACTION = 0.20

    fun pathFor(bounds: ScreenBounds, direction: SwipeDirection): SwipePath {
        require(bounds.width > 1) { "Bounds width must be greater than 1." }
        require(bounds.height > 1) { "Bounds height must be greater than 1." }

        val insetX = insetFor(bounds.width)
        val insetY = insetFor(bounds.height)
        val left = bounds.left + insetX
        val right = bounds.left + bounds.width - 1 - insetX
        val top = bounds.top + insetY
        val bottom = bounds.top + bounds.height - 1 - insetY
        val centerX = bounds.left + ((bounds.width - 1) / 2.0).roundToInt()
        val centerY = bounds.top + ((bounds.height - 1) / 2.0).roundToInt()

        return when (direction) {
            SwipeDirection.LEFT -> SwipePath(
                start = ScreenPixelPoint(x = left, y = centerY),
                end = ScreenPixelPoint(x = right, y = centerY)
            )

            SwipeDirection.RIGHT -> SwipePath(
                start = ScreenPixelPoint(x = right, y = centerY),
                end = ScreenPixelPoint(x = left, y = centerY)
            )

            SwipeDirection.UP -> SwipePath(
                start = ScreenPixelPoint(x = centerX, y = top),
                end = ScreenPixelPoint(x = centerX, y = bottom)
            )

            SwipeDirection.DOWN -> SwipePath(
                start = ScreenPixelPoint(x = centerX, y = bottom),
                end = ScreenPixelPoint(x = centerX, y = top)
            )
        }
    }

    private fun insetFor(size: Int): Int {
        val rawInset = (size * EDGE_INSET_FRACTION).roundToInt()
        val maxInset = ((size - 2) / 2).coerceAtLeast(0)
        return rawInset.coerceIn(0, maxInset)
    }
}
