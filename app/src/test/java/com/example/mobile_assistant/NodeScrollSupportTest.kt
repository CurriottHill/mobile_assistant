package com.example.mobile_assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeScrollSupportTest {
    @Test
    fun fromRaw_parsesUpAndDownCaseInsensitively() {
        assertEquals(NodeScrollDirection.UP, NodeScrollDirection.fromRaw("UP"))
        assertEquals(NodeScrollDirection.DOWN, NodeScrollDirection.fromRaw(" down "))
        assertNull(NodeScrollDirection.fromRaw("left"))
    }
}
