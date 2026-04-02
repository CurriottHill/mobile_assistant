package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySwipeMathTest {
    @Test
    fun pathFor_leftSwipeUsesInteriorHorizontalBand() {
        val path = GallerySwipeMath.pathFor(
            bounds = ScreenBounds(width = 1000, height = 2000, left = 50, top = 100),
            direction = SwipeDirection.LEFT
        )

        assertEquals(250, path.start.x)
        assertEquals(849, path.end.x)
        assertEquals(1100, path.start.y)
        assertEquals(1100, path.end.y)
    }

    @Test
    fun pathFor_upSwipeUsesInteriorVerticalBand() {
        val path = GallerySwipeMath.pathFor(
            bounds = ScreenBounds(width = 1200, height = 900, left = 10, top = 20),
            direction = SwipeDirection.UP
        )

        assertEquals(610, path.start.x)
        assertEquals(610, path.end.x)
        assertEquals(200, path.start.y)
        assertEquals(739, path.end.y)
    }

    @Test
    fun fromRaw_acceptsCaseInsensitiveDirections() {
        assertEquals(SwipeDirection.LEFT, SwipeDirection.fromRaw("LEFT"))
        assertEquals(SwipeDirection.DOWN, SwipeDirection.fromRaw(" down "))
        assertTrue(SwipeDirection.fromRaw("sideways") == null)
    }
}
