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

    @Test
    @DisplayName("MediaCodec level constants map to Dolby Vision level numbers")
    fun levelNumbers() {
        assertEquals(1, dolbyVisionLevelNumber(CodecProfileLevel.DolbyVisionLevelHd24))
        assertEquals(6, dolbyVisionLevelNumber(CodecProfileLevel.DolbyVisionLevelUhd24))
        assertEquals(9, dolbyVisionLevelNumber(CodecProfileLevel.DolbyVisionLevelUhd60))
    }

    @Test
    @DisplayName("a decoder's profiles are described with the highest level of each")
    fun describesProfiles() {
        val profileLevels = listOf(
            CodecProfileLevel.DolbyVisionProfileDvheSt to CodecProfileLevel.DolbyVisionLevelUhd30,
            CodecProfileLevel.DolbyVisionProfileDvheStn to CodecProfileLevel.DolbyVisionLevelUhd60,
            CodecProfileLevel.DolbyVisionProfileDvheSt to CodecProfileLevel.DolbyVisionLevelUhd60,
        )

        assertEquals("profile 5 up to level 9, profile 8 up to level 9", describeProfileLevels(profileLevels))
        assertEquals("no profiles", describeProfileLevels(emptyList()))
    }
}
