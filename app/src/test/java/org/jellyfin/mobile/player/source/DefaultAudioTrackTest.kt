package org.jellyfin.mobile.player.source

import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

class DefaultAudioTrackTest {
    // Back to the Future Part II: a Dolby Vision Profile 7 remux with two tracks flagged default
    private val video = mockk<MediaStream>(relaxed = true) {
        every { index } returns 0
        every { type } returns MediaStreamType.VIDEO
        every { dvProfile } returns 7
    }
    private val trueHd = audio(1, "truehd", channels = 8, title = "TrueHD Atmos 7.1", isDefault = true, atmos = true)
    private val eac3Atmos = audio(2, "eac3", channels = 6, title = "DDP Atmos 5.1", isDefault = true, atmos = true)
    private val commentary = audio(3, "ac3", channels = 2, title = "Q&A Commentary by director Robert Zemeckis")

    @Test
    @DisplayName("an mp4 transcode prefers the E-AC-3 Atmos track, even uncapped and for Dolby Vision Profile 7")
    fun mp4TranscodePrefersEac3() {
        val source = source(listOf(video, trueHd, eac3Atmos, commentary), transcodingContainer = "mp4")

        assertEquals(2, source.resolveDefaultAudioTrack(maxBitrate = null)?.index)
        assertEquals(2, source.resolveDefaultAudioTrack(maxBitrate = 40_000_000)?.index)
        assertEquals(2, source.resolveDefaultAudioTrack(maxBitrate = 15_000_000)?.index)
    }

    @Test
    @DisplayName("direct play and ts keep the file's default, as nothing would be copied")
    fun keepsDefaultWithoutMp4Transcode() {
        val streams = listOf(video, trueHd, eac3Atmos, commentary)

        assertEquals(1, source(streams, directPlay = true).resolveDefaultAudioTrack(maxBitrate = null)?.index)
        assertEquals(1, source(streams, transcodingContainer = "ts").resolveDefaultAudioTrack(maxBitrate = null)?.index)
    }

    @Test
    @DisplayName("below the multichannel cap the default is kept, since the downmix re-encodes anyway")
    fun keepsDefaultWhenDownmixing() {
        val source = source(listOf(video, trueHd, eac3Atmos), transcodingContainer = "mp4")

        assertEquals(1, source.resolveDefaultAudioTrack(maxBitrate = 5_000_000)?.index)
    }

    @Test
    @DisplayName("a stereo commentary track is never preferred over the programme")
    fun ignoresCommentary() {
        val source = source(listOf(video, trueHd, commentary), transcodingContainer = "mp4")

        assertEquals(1, source.resolveDefaultAudioTrack(maxBitrate = null)?.index)
    }

    @Test
    @DisplayName("a commentary track is never preferred, even in 5.1")
    fun ignoresSurroundCommentary() {
        val surroundCommentary = audio(2, "eac3", channels = 6, title = "Commentary with Bob Gale")
        val source = source(listOf(video, trueHd, surroundCommentary), transcodingContainer = "mp4")

        assertEquals(1, source.resolveDefaultAudioTrack(maxBitrate = null)?.index)
    }

    @Test
    @DisplayName("a 5.1 AC-3 track in the same language is copied instead of re-encoding TrueHD")
    fun prefersAc3CompatibilityTrack() {
        val ac3 = audio(2, "ac3", channels = 6)
        val source = source(listOf(video, trueHd, ac3), transcodingContainer = "mp4")

        assertEquals(2, source.resolveDefaultAudioTrack(maxBitrate = null)?.index)
    }

    @Test
    @DisplayName("a track in another language is never preferred")
    fun ignoresOtherLanguages() {
        val dubbed = audio(2, "eac3", channels = 6, language = "fre", atmos = true)
        val source = source(listOf(video, trueHd, dubbed), transcodingContainer = "mp4")

        assertEquals(1, source.resolveDefaultAudioTrack(maxBitrate = null)?.index)
    }

    @Suppress("LongParameterList") // Named arguments with defaults, one per track property that matters
    private fun audio(
        index: Int,
        codec: String,
        channels: Int,
        title: String? = null,
        language: String = "eng",
        isDefault: Boolean = false,
        atmos: Boolean = false,
    ): MediaStream = mockk(relaxed = true) {
        every { this@mockk.index } returns index
        every { type } returns MediaStreamType.AUDIO
        every { this@mockk.codec } returns codec
        every { this@mockk.channels } returns channels
        every { this@mockk.title } returns title
        every { comment } returns null
        every { this@mockk.language } returns language
        every { this@mockk.isDefault } returns isDefault
        every { audioSpatialFormat } returns if (atmos) AudioSpatialFormat.DOLBY_ATMOS else AudioSpatialFormat.NONE
    }

    private fun source(
        streams: List<MediaStream>,
        directPlay: Boolean = false,
        transcodingContainer: String? = null,
    ): JellyfinMediaSource {
        val sourceInfo = mockk<MediaSourceInfo>(relaxed = true) {
            every { id } returns "source"
            every { runTimeTicks } returns null
            every { mediaStreams } returns streams
            every { defaultAudioStreamIndex } returns null
            every { defaultSubtitleStreamIndex } returns null
            every { supportsDirectPlay } returns directPlay
            every { supportsDirectStream } returns false
            every { supportsTranscoding } returns true
            every { this@mockk.transcodingContainer } returns transcodingContainer
        }
        return RemoteJellyfinMediaSource(
            itemId = UUID.randomUUID(),
            item = null,
            sourceInfo = sourceInfo,
            playSessionId = "session",
            liveStreamId = null,
            maxStreamingBitrate = null,
            playbackDetails = null,
        )
    }
}
