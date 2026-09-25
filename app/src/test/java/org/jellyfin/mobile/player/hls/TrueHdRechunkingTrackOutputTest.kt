package org.jellyfin.mobile.player.hls

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TrueHdRechunkingTrackOutputTest {
    private val trueHd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).build()
    private val eac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC).build()

    @Test
    @DisplayName("TrueHD access units are passed on 16 at a time, starting at the first sync frame")
    fun groupsAccessUnits() {
        val output = RecordingTrackOutput()
        val rechunker = TrueHdRechunkingTrackOutput(output)
        rechunker.format(trueHd)

        // A segment starting 3 access units before a sync frame, then two groups and a bit
        for (i in 0 until 3 + 16 + 16 + 5) {
            val isSyncFrame = i == 3
            rechunker.sampleMetadata(
                timeUs = i * ACCESS_UNIT_US,
                flags = if (isSyncFrame) C.BUFFER_FLAG_KEY_FRAME else 0,
                size = 100 + i,
                offset = 0,
                cryptoData = null,
            )
        }

        assertEquals(2, output.samples.size)
        val first = output.samples[0]
        assertEquals(3 * ACCESS_UNIT_US, first.timeUs)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, first.flags)
        assertEquals((3 until 19).sumOf { 100 + it }, first.size)
        val second = output.samples[1]
        assertEquals(19 * ACCESS_UNIT_US, second.timeUs)
        assertEquals(0, second.flags)
        assertEquals((19 until 35).sumOf { 100 + it }, second.size)
    }

    @Test
    @DisplayName("a gap between access units starts the grouping over at the next sync frame")
    fun startsOverAfterGap() {
        val output = RecordingTrackOutput()
        val rechunker = TrueHdRechunkingTrackOutput(output)
        rechunker.format(trueHd)

        // As when the server starts transcoding at a seek point: a sync frame, then a 63 ms gap
        rechunker.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, 1_272, 0, null)
        val resumeUs = 63_000L
        for (i in 0 until 128 + 16) {
            val flags = if (i == 128) C.BUFFER_FLAG_KEY_FRAME else 0
            rechunker.sampleMetadata(resumeUs + i * ACCESS_UNIT_US, flags, 400, 0, null)
        }

        assertEquals(listOf(resumeUs + 128 * ACCESS_UNIT_US), output.samples.map { it.timeUs })
        assertEquals(16 * 400, output.samples.single().size)
    }

    @Test
    @DisplayName("timestamps wandering by a millisecond, as Matroska's do, are not a gap")
    fun toleratesJitter() {
        val output = RecordingTrackOutput()
        val rechunker = TrueHdRechunkingTrackOutput(output)
        rechunker.format(trueHd)

        var timeUs = 0L
        for (i in 0 until 32) {
            rechunker.sampleMetadata(timeUs, if (i == 0) C.BUFFER_FLAG_KEY_FRAME else 0, 400, 0, null)
            timeUs += if (i % 2 == 0) 333L else 1_333L
        }

        assertEquals(2, output.samples.size)
    }

    @Test
    @DisplayName("other formats pass through one sample at a time")
    fun passesOtherFormatsThrough() {
        val output = RecordingTrackOutput()
        val rechunker = TrueHdRechunkingTrackOutput(output)
        rechunker.format(eac3)

        repeat(3) { i -> rechunker.sampleMetadata(i * 32_000L, 0, 1_536, 0, null) }

        assertEquals(listOf(0L, 32_000L, 64_000L), output.samples.map { it.timeUs })
    }

    private class RecordingTrackOutput : TrackOutput {
        data class Sample(val timeUs: Long, val flags: Int, val size: Int, val offset: Int)

        val samples = ArrayList<Sample>()

        override fun format(format: Format) = Unit

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            error("Not used")

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = error("Not used")

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            samples += Sample(timeUs, flags, size, offset)
        }
    }

    private companion object {
        const val ACCESS_UNIT_US = 833L // 1/1200 s
    }
}
