package org.jellyfin.mobile.player.subtitles

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class SupReaderTest {
    private val pcs = 0x16
    private val wds = 0x17
    private val pds = 0x14
    private val ods = 0x15
    private val end = 0x80

    @Test
    @DisplayName("splits a .sup stream into display sets without the PG headers")
    fun splitsDisplaySets() {
        // A subtitle shown at 35.202 s and cleared at 37.538 s, as in a real extracted track
        val sup = sup(
            segment(3_168_180, pcs, 19),
            segment(3_168_180, wds, 10),
            segment(3_168_180, pds, 7),
            segment(3_168_180, ods, 30),
            segment(3_168_180, end, 0),
            segment(3_378_420, pcs, 11),
            segment(3_378_420, wds, 10),
            segment(3_378_420, end, 0),
        )
        val reader = SupReader(trickle(sup))

        val shown = requireNotNull(reader.next())
        assertEquals(35_202_000L, shown.timeUs)
        assertArrayEquals(segments(pcs to 19, wds to 10, pds to 7, ods to 30, end to 0), shown.data)

        val cleared = requireNotNull(reader.next())
        assertEquals(37_538_000L, cleared.timeUs)
        assertArrayEquals(segments(pcs to 11, wds to 10, end to 0), cleared.data)

        assertNull(reader.next())
    }

    @Test
    @DisplayName("a display set cut off by the end of the stream is dropped")
    fun dropsTruncatedDisplaySet() {
        val complete = sup(segment(90_000, pcs, 19), segment(90_000, end, 0))
        val truncated = sup(segment(180_000, pcs, 19), segment(180_000, ods, 40)).copyOf(20)
        val reader = SupReader(ByteArrayInputStream(complete + truncated))

        assertEquals(1_000_000L, requireNotNull(reader.next()).timeUs)
        assertNull(reader.next())
    }

    @Test
    @DisplayName("anything that is not a .sup stream is rejected")
    fun rejectsOtherData() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()
        assertThrows(IOException::class.java) { SupReader(ByteArrayInputStream(srt)).next() }
    }

    /** The segment header and payload as stored in a .sup file. */
    private fun segment(pts: Long, type: Int, size: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('P'.code)
        out.write('G'.code)
        for (shift in intArrayOf(24, 16, 8, 0)) out.write((pts shr shift).toInt() and 0xFF)
        repeat(4) { out.write(0) } // DTS, unused
        out.write(type)
        out.write(size shr 8)
        out.write(size and 0xFF)
        out.write(payload(type, size))
        return out.toByteArray()
    }

    /** The same segments as media3's PgsParser takes them: type, size, payload. */
    private fun segments(vararg typesAndSizes: Pair<Int, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, size) in typesAndSizes) {
            out.write(type)
            out.write(size shr 8)
            out.write(size and 0xFF)
            out.write(payload(type, size))
        }
        return out.toByteArray()
    }

    private fun payload(type: Int, size: Int) = ByteArray(size) { i -> (type + i).toByte() }

    private fun sup(vararg segments: ByteArray) = segments.reduce(ByteArray::plus)

    /** An input stream that returns at most 3 bytes per read, as a network stream may. */
    private fun trickle(data: ByteArray): InputStream = object : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == data.size) return -1
            val count = minOf(length, 3, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
