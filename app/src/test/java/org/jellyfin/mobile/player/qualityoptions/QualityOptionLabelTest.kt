package org.jellyfin.mobile.player.qualityoptions

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.mobile.R
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

class QualityOptionLabelTest {
    private val context = mockk<Context> {
        every { getString(R.string.menu_item_auto) } returns "Auto"
    }
    private lateinit var defaultLocale: Locale

    @BeforeEach
    fun useEnglishNumbers() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    @DisplayName("a quality option is labelled with its bitrate alone, as the server picks the resolution")
    fun labelsBitrateOnly() {
        assertEquals("4 Mbps", QualityOption(minSourceHeight = 720, bitrate = 4_000_000).getLabel(context))
        assertEquals("120 Mbps", QualityOption(minSourceHeight = 2160, bitrate = 120_000_000).getLabel(context))
        assertEquals("720 kbps", QualityOption(minSourceHeight = 480, bitrate = 720_000).getLabel(context))
        assertEquals("Auto", QualityOption(minSourceHeight = 0, bitrate = 0).getLabel(context))
    }
}
