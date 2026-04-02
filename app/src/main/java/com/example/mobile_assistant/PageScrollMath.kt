package com.example.mobile_assistant

import kotlin.math.roundToInt

internal data class PageScrollPath(
    val start: ScreenPixelPoint,
    val end: ScreenPixelPoint
)

internal enum class PageScrollDirection(val wireValue: String) {
    UP("up"),
    DOWN("down");

    companion object {
        fun fromRaw(raw: String?): PageScrollDirection? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

internal object PageScrollMath {
    private const val HORIZONTAL_SCROLL_FRACTION = 0.82
    private const val VERTICAL_EDGE_INSET_FRACTION = 0.20

    fun pathFor(bounds: ScreenBounds, direction: PageScrollDirection): PageScrollPath {
        require(bounds.width > 1) { "Bounds width must be greater than 1." }
        require(bounds.height > 1) { "Bounds height must be greater than 1." }

        val x = bounds.left + ((bounds.width - 1) * HORIZONTAL_SCROLL_FRACTION).roundToInt()
        val insetY = insetFor(bounds.height)
        val top = bounds.top + insetY
        val bottom = bounds.top + bounds.height - 1 - insetY

        return when (direction) {
            PageScrollDirection.DOWN -> PageScrollPath(
                start = ScreenPixelPoint(x = x, y = bottom),
                end = ScreenPixelPoint(x = x, y = top)
            )

            PageScrollDirection.UP -> PageScrollPath(
                start = ScreenPixelPoint(x = x, y = top),
                end = ScreenPixelPoint(x = x, y = bottom)
            )
        }
    }

    private fun insetFor(size: Int): Int {
        val rawInset = (size * VERTICAL_EDGE_INSET_FRACTION).roundToInt()
        val maxInset = ((size - 2) / 2).coerceAtLeast(0)
        return rawInset.coerceIn(0, maxInset)
    }
}
