package org.jellyfin.mobile.player.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PictureFitTest {
    private val width = 480
    private val height = 270

    /** A frame with picture (dark grey with grain) inside the given columns and rows, black around it. */
    private fun frame(
        pictureLeft: Int,
        pictureTop: Int,
        pictureRight: Int,
        pictureBottom: Int,
        level: Int = 30,
    ): IntArray {
        val random = Random(1)
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val inside = x in pictureLeft until pictureRight && y in pictureTop until pictureBottom
            val value = if (inside) level + random.nextInt(-6, 7) else random.nextInt(0, 3)
            (0xff shl 24) or (value shl 16) or (value shl 8) or value
        }
    }

    @Test
    @DisplayName("a letterboxed 2.39:1 picture is measured to its rows")
    fun letterbox() {
        val area = PictureMeasurement.measure(frame(0, 34, width, 236), width, height)!!
        assertEquals(0f, area.left)
        assertEquals(1f, area.right)
        assertEquals(34f / height, area.top, 0.001f)
        assertEquals(236f / height, area.bottom, 0.001f)
    }

    @Test
    @DisplayName("an all-black frame says nothing about the area")
    fun black() {
        assertNull(PictureMeasurement.measure(frame(0, 0, 0, 0), width, height))
    }

    @Test
    @DisplayName("dark picture with grain is not taken for a bar")
    fun darkPicture() {
        val area = PictureMeasurement.measure(frame(0, 0, width, height, level = 12), width, height)!!
        assertTrue(area.isFullFrame)
    }

    @Test
    @DisplayName("bars zoom in only after half a second of agreement")
    fun zoomInWaits() {
        val decision = PictureFitDecision()
        val letterbox = PictureArea(0f, 0.125f, 1f, 0.875f)
        assertFalse(decision.step(0, letterbox))
        assertFalse(decision.step(250, letterbox))
        assertTrue(decision.step(500, letterbox))
        // With its margin: never into the picture.
        assertTrue(decision.decided.top < 0.125f && decision.decided.bottom > 0.875f)
    }

    @Test
    @DisplayName("picture outside the area zooms out at once")
    fun zoomOutAtOnce() {
        val decision = PictureFitDecision()
        val letterbox = PictureArea(0f, 0.125f, 1f, 0.875f)
        decision.step(0, letterbox)
        decision.step(500, letterbox)
        assertTrue(decision.step(750, PictureArea.FULL_FRAME))
        assertEquals(PictureArea.FULL_FRAME, decision.decided)
    }

    @Test
    @DisplayName("bars on all four sides never zoom in")
    fun windowbox() {
        val decision = PictureFitDecision()
        val boxed = PictureArea(0.1f, 0.1f, 0.9f, 0.9f)
        decision.step(0, boxed)
        decision.step(500, boxed)
        assertFalse(decision.step(1000, boxed))
        assertEquals(PictureArea.FULL_FRAME, decision.decided)
    }

    @Test
    @DisplayName("a seek back starts the half second again")
    fun seekBack() {
        val decision = PictureFitDecision()
        val letterbox = PictureArea(0f, 0.125f, 1f, 0.875f)
        decision.step(10_000, letterbox)
        assertFalse(decision.step(2_000, letterbox))
        assertFalse(decision.step(2_250, letterbox))
        assertTrue(decision.step(2_500, letterbox))
    }
}
