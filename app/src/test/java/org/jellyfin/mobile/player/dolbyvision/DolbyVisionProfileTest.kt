package org.jellyfin.mobile.player.dolbyvision

import org.jellyfin.mobile.utils.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DolbyVisionRewriteModeTest {
    @Test
    @DisplayName("automatic leaves the stream alone where the hardware can decode it")
    fun automaticDefersToHardware() {
        assertFalse(shouldRewriteProfile7(Constants.DV_PROFILE_7_AUTOMATIC, decoderSupportsProfile7 = true))
        assertTrue(shouldRewriteProfile7(Constants.DV_PROFILE_7_AUTOMATIC, decoderSupportsProfile7 = false))
    }

    @Test
    @DisplayName("base layer overrides the hardware, for decoders that mishandle dual layer")
    fun baseLayerIgnoresHardware() {
        assertTrue(shouldRewriteProfile7(Constants.DV_PROFILE_7_BASE_LAYER, decoderSupportsProfile7 = true))
        assertTrue(shouldRewriteProfile7(Constants.DV_PROFILE_7_BASE_LAYER, decoderSupportsProfile7 = false))
    }

    @Test
    @DisplayName("never rewrites nothing, whatever the hardware")
    fun neverRewritesNothing() {
        assertFalse(shouldRewriteProfile7(Constants.DV_PROFILE_7_NEVER, decoderSupportsProfile7 = true))
        assertFalse(shouldRewriteProfile7(Constants.DV_PROFILE_7_NEVER, decoderSupportsProfile7 = false))
    }

    @Test
    @DisplayName("an unrecognised stored value behaves as automatic rather than disabling playback")
    fun unknownValueFallsBackToAutomatic() {
        assertTrue(shouldRewriteProfile7("something_from_a_future_version", decoderSupportsProfile7 = false))
        assertFalse(shouldRewriteProfile7("something_from_a_future_version", decoderSupportsProfile7 = true))
    }
}

/**
 * The profile number decides whether a track is rewritten, and rewriting the wrong one is worse
 * than rewriting none: Profile 5 presented as HEVC decodes to wildly wrong colour rather than
 * failing visibly. So the cases that must return something other than 7 matter more here than
 * the case that must return 7.
 */
class DolbyVisionProfileTest {
    @Test
    @DisplayName("reads the profile from a dual-layer Profile 7 codec string")
    fun readsProfile7() {
        assertEquals(7, dolbyVisionProfile("dvhe.07.06"))
        assertEquals(7, dolbyVisionProfile("dvh1.07.06"))
    }

    @Test
    @DisplayName("reads profiles that must NOT be rewritten")
    fun readsOtherProfiles() {
        // IPT base layer - rewriting this would produce wrong colour, not a black frame.
        assertEquals(5, dolbyVisionProfile("dvhe.05.06"))
        // Already HDR10 compatible; media3 falls back on its own.
        assertEquals(8, dolbyVisionProfile("dvhe.08.06"))
        assertEquals(8, dolbyVisionProfile("dvh1.08.09"))
    }

    @Test
    @DisplayName("is case insensitive, as codec strings are not consistently cased")
    fun isCaseInsensitive() {
        assertEquals(7, dolbyVisionProfile("DVHE.07.06"))
        assertEquals(7, dolbyVisionProfile("DvH1.07.06"))
    }

    @Test
    @DisplayName("returns null for codecs that are not Dolby Vision")
    fun ignoresNonDolbyVision() {
        assertNull(dolbyVisionProfile("hvc1.2.4.L153.B0"))
        assertNull(dolbyVisionProfile("hev1.1.6.L93.B0"))
        assertNull(dolbyVisionProfile("avc1.640028"))
        assertNull(dolbyVisionProfile("av01.0.08M.10"))
    }

    @Test
    @DisplayName("returns null rather than throwing on absent or malformed strings")
    fun toleratesMalformedInput() {
        assertNull(dolbyVisionProfile(null))
        assertNull(dolbyVisionProfile(""))
        assertNull(dolbyVisionProfile("dvhe"))
        assertNull(dolbyVisionProfile("dvhe."))
        assertNull(dolbyVisionProfile("dvhe.xx.06"))
    }

    @Test
    @DisplayName("does not mistake a codec merely starting with the same letters")
    fun doesNotPrefixMatch() {
        // Guards against matching on startsWith rather than the whole first component.
        assertNull(dolbyVisionProfile("dvheX.07.06"))
        assertNull(dolbyVisionProfile("xdvhe.07.06"))
    }
}
