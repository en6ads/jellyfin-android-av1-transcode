package org.jellyfin.mobile.player.deviceprofile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TranscodeVideoRangesTest {
    @Test
    @DisplayName("an SDR display with no Dolby Vision decoder is only offered SDR")
    fun sdrDisplay() {
        for (codec in listOf("av1", "hevc", "h264")) {
            assertEquals(listOf("SDR"), transcodeVideoRangeTypes(codec, emptySet(), emptySet()))
        }
    }

    @Test
    @DisplayName("HDR types follow what the display reports")
    fun hdrDisplay() {
        assertEquals(
            listOf("SDR", "HDR10", "HLG"),
            transcodeVideoRangeTypes("av1", setOf(HDR_TYPE_HDR10, HDR_TYPE_HLG), emptySet()),
        )
        assertEquals(
            listOf("SDR", "HDR10", "HDR10Plus", "HLG"),
            transcodeVideoRangeTypes("hevc", setOf(HDR_TYPE_HDR10, HDR_TYPE_HDR10_PLUS, HDR_TYPE_HLG, HDR_TYPE_DOLBY_VISION), emptySet()),
        )
    }

    @Test
    @DisplayName("Dolby Vision types follow the profiles the decoder advertises for the codec")
    fun dolbyVision() {
        val hdr10 = setOf(HDR_TYPE_HDR10)

        // A typical phone decoder: profiles 5 and 8, no Profile 7.
        assertEquals(
            listOf("SDR", "HDR10", "DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus"),
            transcodeVideoRangeTypes("hevc", hdr10, setOf(5, 8)),
        )
        assertEquals(
            listOf("SDR", "HDR10", "DOVIWithEL", "DOVIWithELHDR10Plus"),
            transcodeVideoRangeTypes("hevc", hdr10, setOf(7)),
        )

        // HEVC profiles say nothing about AV1, and the reverse.
        assertEquals(listOf("SDR", "HDR10"), transcodeVideoRangeTypes("av1", hdr10, setOf(5, 7, 8)))
        assertEquals(
            listOf("SDR", "HDR10", "DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR"),
            transcodeVideoRangeTypes("av1", hdr10, setOf(10)),
        )
        assertEquals(listOf("SDR", "HDR10"), transcodeVideoRangeTypes("h264", hdr10, setOf(5, 8, 10)))
    }
}
