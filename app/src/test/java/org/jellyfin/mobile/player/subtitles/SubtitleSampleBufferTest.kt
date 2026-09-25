package org.jellyfin.mobile.player.subtitles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SubtitleSampleBufferTest {
    @Test
    @DisplayName("replacing cues: a seek starts at the last cue that began at or before the position")
    fun replacingCuesSeek() {
        val buffer = SubtitleSampleBuffer(replacesCues = true)
        buffer.add(35_000_000, Long.MAX_VALUE, byteArrayOf(1)) // shown
        buffer.add(37_000_000, Long.MAX_VALUE, byteArrayOf()) // cleared
        buffer.add(48_000_000, Long.MAX_VALUE, byteArrayOf(2)) // shown

        assertEquals(0, buffer.indexForPosition(10_000_000))
        assertEquals(0, buffer.indexForPosition(36_000_000))
        assertEquals(1, buffer.indexForPosition(40_000_000))
        assertEquals(2, buffer.indexForPosition(50_000_000))
    }

    @Test
    @DisplayName("overlapping cues: a seek starts at the first cue still showing, even if a later one has ended")
    fun overlappingCuesSeek() {
        val buffer = SubtitleSampleBuffer(replacesCues = false)
        buffer.add(10_000_000, 20_000_000, byteArrayOf(1)) // long cue
        buffer.add(12_000_000, 13_000_000, byteArrayOf(2)) // short cue inside it
        buffer.add(30_000_000, 31_000_000, byteArrayOf(3))

        assertEquals(0, buffer.indexForPosition(14_000_000))
        assertEquals(2, buffer.indexForPosition(25_000_000))
        assertEquals(3, buffer.indexForPosition(40_000_000))
    }

    @Test
    @DisplayName("seeking before anything has loaded reads from the first sample that arrives")
    fun seekBeforeLoaded() {
        val buffer = SubtitleSampleBuffer(replacesCues = true)
        assertEquals(0, buffer.indexForPosition(60_000_000))
    }
}
