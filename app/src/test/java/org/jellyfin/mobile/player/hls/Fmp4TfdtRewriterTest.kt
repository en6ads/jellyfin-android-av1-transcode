package org.jellyfin.mobile.player.hls

import androidx.media3.common.C
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The fixture was produced by ffmpeg with the arguments Jellyfin uses for fMP4 HLS before the fix for
 * jellyfin/jellyfin#18049: `-copyts -avoid_negative_ts disabled` with `frag_discont`. Track 1 is video
 * at a 10240 Hz timescale, track 2 AAC audio at 48000 Hz, whose 1024 sample priming delay makes the first
 * audio fragment start at -1024.
 */
class Fmp4TfdtRewriterTest {
    private val init = resource("init.mp4")
    private val segment0 = resource("seg0.mp4")
    private val segment1 = resource("seg1.mp4")

    @Test
    @DisplayName("the original first segment has a negative audio decode time")
    fun fixtureReproducesTheProblem() {
        assertEquals(mapOf(1 to 0L, 2 to -1024L), decodeTimes(segment0))
        assertEquals(mapOf(1 to 10240L, 2 to 48128L), decodeTimes(segment1))
    }

    @Test
    @DisplayName("every track of every segment is shifted by the same duration")
    fun shiftsAllTracksTogether() {
        val sessions = TfdtSessions()
        assertArrayEquals(init, rewrite(url(-1), init, sessions))

        val fixed0 = rewrite(url(0), segment0, sessions)
        val fixed1 = rewrite(url(1), segment1, sessions)

        // 1024 / 48000 s is 1024 audio ticks and ceil(218.45) = 219 video ticks
        assertEquals(mapOf(1 to 219L, 2 to 0L), decodeTimes(fixed0))
        assertEquals(mapOf(1 to 10240L + 219, 2 to 48128L + 1024), decodeTimes(fixed1))

        // Only the two 64-bit decode times may change
        assertTrue(differingBytes(segment0, fixed0) <= 16)
        assertTrue(differingBytes(segment1, fixed1) <= 16)
    }

    @Test
    @DisplayName("no decode time is left with its top bit set, which is what media3 rejects")
    fun noNegativeDecodeTimesRemain() {
        val sessions = TfdtSessions()
        rewrite(url(-1), init, sessions)
        for ((index, segment) in listOf(segment0, segment1).withIndex()) {
            assertTrue(decodeTimes(rewrite(url(index), segment, sessions)).values.all { it >= 0 })
        }
    }

    @Test
    @DisplayName("a session that never has a negative decode time passes through byte for byte")
    fun sessionWithoutNegativeTimesIsUntouched() {
        val sessions = TfdtSessions()
        rewrite(url(-1), init, sessions)
        assertArrayEquals(segment1, rewrite(url(1), segment1, sessions))
    }

    @Test
    @DisplayName("the offset applies to other variants of the same session, but not to other sessions")
    fun offsetIsPerSession() {
        val sessions = TfdtSessions()
        rewrite(url(-1), init, sessions)
        rewrite(url(0), segment0, sessions)

        val otherVariant = "&VideoCodec=av1"
        rewrite(url(-1, extra = otherVariant), init, sessions)
        val otherVariantSegment = rewrite(url(1, extra = otherVariant), segment1, sessions)
        assertEquals(mapOf(1 to 10240L + 219, 2 to 48128L + 1024), decodeTimes(otherVariantSegment))

        val otherSession = "&PlaySessionId=two"
        rewrite(url(-1, session = otherSession), init, sessions)
        assertArrayEquals(segment1, rewrite(url(1, session = otherSession), segment1, sessions))
    }

    @Test
    @DisplayName("without the initialization segment, negative decode times are clamped so the segment is readable")
    fun clampsWithoutTimescales() {
        assertEquals(mapOf(1 to 0L, 2 to 0L), decodeTimes(rewrite(url(0), segment0, TfdtSessions())))
    }

    @Test
    @DisplayName("only Jellyfin HLS fMP4 segment URLs are matched")
    fun matchesSegmentUrls() {
        assertNull(HlsSegmentKey.of("http://server:8096/Videos/abc/stream?static=true"))
        assertNull(HlsSegmentKey.of("http://server:8096/videos/abc/hls1/main/0.ts?PlaySessionId=one"))
        assertEquals(HlsSegmentKey.of(url(-1)), HlsSegmentKey.of(url(3)))
    }

    private fun url(segment: Int, session: String = "&PlaySessionId=one", extra: String = "") =
        "http://server:8096/videos/abc/hls1/main/$segment.mp4?VideoCodec=hevc$session$extra" +
            "&runtimeTicks=${segment * 30_000_000L}&actualSegmentLengthTicks=30000000"

    /**
     * Reads through the rewriter with a source that returns at most 7 bytes per call and a reader that
     * asks for 5 at a time, so boxes always straddle reads.
     */
    private fun rewrite(url: String, data: ByteArray, sessions: TfdtSessions): ByteArray {
        var position = 0
        val source = ByteReader { buffer, offset, length ->
            if (position == data.size) return@ByteReader C.RESULT_END_OF_INPUT
            val count = minOf(length, 7, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            count
        }
        val rewriter = Fmp4TfdtRewriter(source, requireNotNull(HlsSegmentKey.of(url)), sessions)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(5)
        while (true) {
            val read = rewriter.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(buffer, 0, read)
        }
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/hls-negative-tfdt/$name")).use { it.readBytes() }

    private fun differingBytes(a: ByteArray, b: ByteArray): Int {
        assertEquals(a.size, b.size)
        return a.indices.count { a[it] != b[it] }
    }

    /** Track ID to tfdt baseMediaDecodeTime, read independently of the code under test. */
    private fun decodeTimes(segment: ByteArray): Map<Int, Long> {
        val result = HashMap<Int, Long>()
        children(segment, 0, segment.size).filter { it.first == "moof" }.forEach { (_, moofStart, moofEnd) ->
            children(segment, moofStart, moofEnd).filter { it.first == "traf" }.forEach { (_, trafStart, trafEnd) ->
                val boxes = children(segment, trafStart, trafEnd)
                val tfhd = boxes.first { it.first == "tfhd" }.second
                val tfdt = boxes.first { it.first == "tfdt" }.second
                val trackId = java.nio.ByteBuffer.wrap(segment, tfhd + 4, 4).int
                val time = when (segment[tfdt].toInt()) {
                    1 -> java.nio.ByteBuffer.wrap(segment, tfdt + 4, 8).long
                    else -> java.nio.ByteBuffer.wrap(segment, tfdt + 4, 4).int.toLong() and 0xFFFFFFFFL
                }
                result[trackId] = time
            }
        }
        return result
    }

    private fun children(data: ByteArray, start: Int, end: Int): List<Triple<String, Int, Int>> {
        val result = ArrayList<Triple<String, Int, Int>>()
        var position = start
        while (position + 8 <= end) {
            val size = java.nio.ByteBuffer.wrap(data, position, 4).int
            result += Triple(String(data, position + 4, 4, Charsets.US_ASCII), position + 8, position + size)
            position += size
        }
        return result
    }
}
