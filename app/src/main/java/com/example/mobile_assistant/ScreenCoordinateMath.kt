package com.example.mobile_assistant

import kotlin.math.roundToInt

internal data class NormalizedScreenPoint(
    val x: Double,
    val y: Double
)

internal data class ScreenBounds(
    val width: Int,
    val height: Int,
    val left: Int = 0,
    val top: Int = 0
)

internal data class ScreenPixelPoint(
    val x: Int,
    val y: Int
)

internal object ScreenCoordinateMath {
    fun validateNormalizedPoint(point: NormalizedScreenPoint): String? {
        validateNormalizedFraction("x", point.x)?.let { return it }
        validateNormalizedFraction("y", point.y)?.let { return it }
        return null
    }

    fun fullScreenPoint(
        point: NormalizedScreenPoint,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenPixelPoint {
        return ScreenPixelPoint(
            x = scaleFractionToPixel(point.x, screenWidth),
            y = scaleFractionToPixel(point.y, screenHeight)
        )
    }

    fun pointInBounds(point: NormalizedScreenPoint, bounds: ScreenBounds): ScreenPixelPoint {
        return ScreenPixelPoint(
            x = bounds.left + scaleFractionToPixel(point.x, bounds.width),
            y = bounds.top + scaleFractionToPixel(point.y, bounds.height)
        )
    }

    fun centerOfBounds(bounds: ScreenBounds): ScreenPixelPoint {
        return ScreenPixelPoint(
            x = bounds.left + ((bounds.width - 1) / 2.0).roundToInt().coerceAtLeast(0),
            y = bounds.top + ((bounds.height - 1) / 2.0).roundToInt().coerceAtLeast(0)
        )
    }

    private fun validateNormalizedFraction(name: String, value: Double): String? {
        return when {
            !value.isFinite() -> "Invalid $name coordinate. Expected a finite number between 0.0 and 1.0."
            value < 0.0 || value > 1.0 -> "Invalid $name coordinate $value. Expected a normalized fraction between 0.0 and 1.0."
            else -> null
        }
    }

    private fun scaleFractionToPixel(fraction: Double, size: Int): Int {
        require(size > 0) { "Screen dimension must be positive." }
        val scaled = (fraction * size.toDouble()).roundToInt()
        return scaled.coerceIn(0, size - 1)
    }
}
