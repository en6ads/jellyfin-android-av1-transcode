package org.jellyfin.mobile.player.deviceprofile

import androidx.media3.common.MimeTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SubtitleMimeTypeTest {
    @Test
    @DisplayName("subtitle codecs are recognized however Jellyfin capitalizes them")
    fun ignoresCase() {
        assertEquals(MimeTypes.APPLICATION_PGS, CodecHelpers.getSubtitleMimeType("PGSSUB"))
        assertEquals(MimeTypes.APPLICATION_PGS, CodecHelpers.getSubtitleMimeType("pgssub"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, CodecHelpers.getSubtitleMimeType("subrip"))
    }
}
