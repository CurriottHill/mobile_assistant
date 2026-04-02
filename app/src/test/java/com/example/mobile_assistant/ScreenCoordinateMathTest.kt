package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenCoordinateMathTest {
    @Test
    fun fullScreenPoint_usesRealScreenSizeAndClampsEdges() {
        val point = ScreenCoordinateMath.fullScreenPoint(
            point = NormalizedScreenPoint(x = 1.0, y = 0.5),
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(ScreenPixelPoint(x = 1079, y = 1200), point)
    }

    @Test
    fun pointInBounds_appliesOffsetsForReusableWindowMath() {
        val point = ScreenCoordinateMath.pointInBounds(
            point = NormalizedScreenPoint(x = 0.25, y = 0.75),
            bounds = ScreenBounds(width = 400, height = 200, left = 10, top = 20)
        )

        assertEquals(ScreenPixelPoint(x = 110, y = 170), point)
    }

    @Test
    fun validateNormalizedPoint_acceptsInclusiveRange() {
        assertNull(
            ScreenCoordinateMath.validateNormalizedPoint(
                NormalizedScreenPoint(x = 0.0, y = 1.0)
            )
        )
    }

    @Test
    fun validateNormalizedPoint_rejectsOutOfRangeValues() {
        assertEquals(
            "Invalid x coordinate 1.2. Expected a normalized fraction between 0.0 and 1.0.",
            ScreenCoordinateMath.validateNormalizedPoint(
                NormalizedScreenPoint(x = 1.2, y = 0.4)
            )
        )
    }
}
