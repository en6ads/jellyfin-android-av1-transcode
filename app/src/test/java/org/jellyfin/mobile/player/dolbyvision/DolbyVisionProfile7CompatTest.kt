package org.jellyfin.mobile.player.dolbyvision

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DolbyVisionProfile7CompatTest {
    @Test
    fun readsProfileFromCodecString() {
        assertEquals(7, dolbyVisionProfile("dvhe.07.06"))
        assertEquals(7, dolbyVisionProfile("DvH1.07.06"))
        assertEquals(5, dolbyVisionProfile("dvhe.05.06"))
        assertEquals(8, dolbyVisionProfile("dvh1.08.09"))
    }

    @Test
    fun ignoresOtherOrMalformedCodecStrings() {
        assertNull(dolbyVisionProfile("hvc1.2.4.L153.B0"))
        assertNull(dolbyVisionProfile("av01.0.08M.10"))
        assertNull(dolbyVisionProfile(null))
        assertNull(dolbyVisionProfile(""))
        assertNull(dolbyVisionProfile("dvhe"))
        assertNull(dolbyVisionProfile("dvhe.xx.06"))
        assertNull(dolbyVisionProfile("dvheX.07.06"))
    }

    @Test
    fun mapsMediaCodecProfileConstants() {
        assertEquals(5, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvheStn))
        assertEquals(7, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvheDtb))
        assertEquals(8, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvheSt))
        assertEquals(10, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvav110))
    }
}
