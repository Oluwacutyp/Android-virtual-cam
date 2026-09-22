package com.androidvirtualcam.scene

import org.junit.Assert.*
import org.junit.Test

class TransformTest {

    @Test
    fun testDefaultValues() {
        val t = Transform()
        assertEquals(0.5f, t.x, 0.001f)
        assertEquals(0.5f, t.y, 0.001f)
        assertEquals(1f, t.scaleX, 0.001f)
        assertEquals(1f, t.alpha, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidAlphaThrows() {
        Transform(alpha = 2f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidScaleThrows() {
        Transform(scaleX = -1f)
    }

    @Test
    fun testMoveAndScaleAndRotate() {
        var t = Transform(x = 0.5f, y = 0.5f, scaleX = 1f, rotationDegrees = 0f)
        t = t.moved(0.1f, -0.1f)
        assertEquals(0.6f, t.x, 0.001f)
        assertEquals(0.4f, t.y, 0.001f)

        t = t.scaled(2f)
        assertEquals(2f, t.scaleX, 0.001f)

        t = t.rotated(45f)
        assertEquals(45f, t.rotationDegrees, 0.001f)

        t = t.rotated(400f)
        assertEquals(85f, t.rotationDegrees, 0.001f) // 45+400=445 %360=85
    }
}
