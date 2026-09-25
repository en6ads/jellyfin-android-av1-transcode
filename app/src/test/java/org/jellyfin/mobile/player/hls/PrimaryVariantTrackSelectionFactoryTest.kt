package org.jellyfin.mobile.player.hls

import android.text.TextUtils
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PrimaryVariantTrackSelectionFactoryTest {
    private val delegated = ArrayList<ExoTrackSelection.Definition?>()
    private val factory = PrimaryVariantTrackSelectionFactory { definitions, _, _, _ ->
        delegated += definitions
        arrayOfNulls(definitions.size)
    }

    // TrackGroup checks its formats' labels with android.text.TextUtils
    @BeforeEach
    fun mockTextUtils() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
    }

    @AfterEach
    fun unmockTextUtils() {
        unmockkStatic(TextUtils::class)
    }

    @Test
    @DisplayName("video variants that share a bitrate are pinned to the first playable one")
    fun pinsSameBitrateVariants() {
        // Track 0 is left out, as the track selector does for a variant the device cannot play
        val group = TrackGroup(video(8_000_000), video(8_000_000), video(8_000_000))
        val definition = ExoTrackSelection.Definition(group, 1, 2)

        val selection = select(definition)[0]

        assertTrue(selection is FixedTrackSelection)
        assertEquals(1, selection!!.selectedIndexInTrackGroup)
        assertEquals(listOf(null), delegated)
    }

    @Test
    @DisplayName("a real bitrate ladder is left to adaptive selection")
    fun delegatesBitrateLadders() {
        val definition = ExoTrackSelection.Definition(TrackGroup(video(8_000_000), video(4_000_000)), 0, 1)

        assertNull(select(definition)[0])
        assertSame(definition, delegated.single())
    }

    @Test
    @DisplayName("audio and single-track definitions are left to the delegate")
    fun delegatesEverythingElse() {
        val audio = ExoTrackSelection.Definition(TrackGroup(audio(), audio()), 0, 1)
        val single = ExoTrackSelection.Definition(TrackGroup(video(8_000_000)), 0)

        select(audio, single)

        assertEquals(listOf(audio, single), delegated)
    }

    private fun select(vararg definitions: ExoTrackSelection.Definition?) =
        factory.createTrackSelections(
            definitions,
            mockk(relaxed = true),
            MediaSource.MediaPeriodId(Any()),
            Timeline.EMPTY,
        )

    private fun video(bitrate: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setPeakBitrate(bitrate)
        .build()

    private fun audio() = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setPeakBitrate(128_000).build()
}
