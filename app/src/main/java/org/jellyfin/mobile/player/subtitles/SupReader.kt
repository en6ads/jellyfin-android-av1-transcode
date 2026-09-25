package org.jellyfin.mobile.player.subtitles

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the display sets of a PGS subtitle stream in the .sup file format.
 *
 * Every segment of a .sup file starts with "PG", a 90 kHz presentation time and a decoding time. A
 * display set is the run of segments up to and including an END segment. [next] returns each display
 * set's segments with those prefixes removed, which is how Matroska stores them and what media3's
 * PgsParser takes.
 */
@Suppress("MagicNumber") // Offsets of the .sup segment header
internal class SupReader(private val input: InputStream) {
    class DisplaySet(val timeUs: Long, val data: ByteArray)

    /**
     * The next complete display set, or null at the end of the stream. A display set cut off by the
     * end of the stream is dropped.
     */
    fun next(): DisplaySet? {
        val segments = ByteArrayOutputStream()
        var timeUs = UNSET
        while (true) {
            val header = readFully(SEGMENT_HEADER_SIZE) ?: return null
            if (header[0] != 'P'.code.toByte() || header[1] != 'G'.code.toByte()) {
                throw IOException("Not a PGS .sup stream")
            }
            val type = header[10].toInt() and BYTE_MASK
            val size = (header[11].toInt() and BYTE_MASK shl 8) or (header[12].toInt() and BYTE_MASK)
            val payload = readFully(size) ?: return null

            if (timeUs == UNSET) timeUs = readUInt32(header, 2) * MICROS_PER_SECOND / PTS_CLOCK_RATE
            segments.write(type)
            segments.write(size shr 8)
            segments.write(size and BYTE_MASK)
            segments.write(payload)

            if (type == END_OF_DISPLAY_SET) return DisplaySet(timeUs, segments.toByteArray())
        }
    }

    private fun readFully(length: Int): ByteArray? {
        val data = ByteArray(length)
        var position = 0
        while (position < length) {
            val read = input.read(data, position, length - position)
            if (read < 0) return null
            position += read
        }
        return data
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, i -> (value shl 8) or (data[offset + i].toLong() and 0xFF) }

    private companion object {
        const val SEGMENT_HEADER_SIZE = 13
        const val END_OF_DISPLAY_SET = 0x80
        const val BYTE_MASK = 0xFF
        const val PTS_CLOCK_RATE = 90_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val UNSET = Long.MIN_VALUE
    }
}
