package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class PageScrollMathTest {
    @Test
    fun pathFor_down_usesCenteredVerticalPathInsideSafeInteriorBand() {
        val path = PageScrollMath.pathFor(
            bounds = ScreenBounds(width = 1000, height = 2000, left = 50, top = 100),
            direction = PageScrollDirection.DOWN
        )

        assertEquals(869, path.start.x)
        assertEquals(869, path.end.x)
        assertEquals(1699, path.start.y)
        assertEquals(500, path.end.y)
    }

    @Test
    fun pathFor_up_reversesTheVerticalGesture() {
        val path = PageScrollMath.pathFor(
            bounds = ScreenBounds(width = 1000, height = 2000, left = 50, top = 100),
            direction = PageScrollDirection.UP
        )

        assertEquals(869, path.start.x)
        assertEquals(869, path.end.x)
        assertEquals(500, path.start.y)
        assertEquals(1699, path.end.y)
    }

    @Test
    fun pathFor_keepsVerySmallBoundsInsideWindow() {
        val path = PageScrollMath.pathFor(
            bounds = ScreenBounds(width = 2, height = 2, left = 7, top = 9),
            direction = PageScrollDirection.DOWN
        )

        assertEquals(8, path.start.x)
        assertEquals(8, path.end.x)
        assertEquals(10, path.start.y)
        assertEquals(9, path.end.y)
    }
}
