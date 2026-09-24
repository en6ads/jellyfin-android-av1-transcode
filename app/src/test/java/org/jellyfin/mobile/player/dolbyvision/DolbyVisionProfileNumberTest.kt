package org.jellyfin.mobile.player.dolbyvision

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DolbyVisionProfileNumberTest {
    @Test
    @DisplayName("MediaCodec profile constants map to Dolby Vision profile numbers")
    fun profileNumbers() {
        assertEquals(0, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvavPer))
        assertEquals(5, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvheStn))
        assertEquals(7, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvheDtb))
        assertEquals(8, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvheSt))
        assertEquals(9, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvavSe))
        assertEquals(10, dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvav110))
    }
}
