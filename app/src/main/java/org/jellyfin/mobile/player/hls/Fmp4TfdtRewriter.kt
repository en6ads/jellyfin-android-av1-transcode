package org.jellyfin.mobile.player.hls

import androidx.media3.common.C
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads up to `length` bytes into `buffer`, returning [C.RESULT_END_OF_INPUT] at the end of the input.
 */
internal fun interface ByteReader {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * Identifies a Jellyfin fMP4 HLS segment. [session] is shared by every segment of one playback, across
 * variants, and [variant] by a variant's media segments and its initialization segment.
 */
internal data class HlsSegmentKey(val session: String, val variant: String) {
    companion object {
        private val SEGMENT_PATH = Regex("/hls1/[^/]+/-?\\d+\\.mp4$", RegexOption.IGNORE_CASE)

        /** Query parameters that differ between segments of the same variant. */
        private val PER_SEGMENT_PARAMETERS = setOf("runtimeticks", "actualsegmentlengthticks")

        fun of(url: String): HlsSegmentKey? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val path = uri.rawPath ?: return null
            val match = SEGMENT_PATH.find(path) ?: return null
            val itemPath = path.substring(0, match.range.first)
            val parameters = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)
            val playSession = parameters.firstOrNull { parameter ->
                parameter.substringBefore('=').equals("PlaySessionId", ignoreCase = true)
            }
            val variantParameters = parameters
                .filter { parameter -> parameter.substringBefore('=').lowercase() !in PER_SEGMENT_PARAMETERS }
                .sorted()
            return HlsSegmentKey(
                session = "$itemPath?$playSession",
                variant = "$itemPath?${variantParameters.joinToString("&")}",
            )
        }
    }
}

/**
 * State shared by all segments loaded through one data source factory: the track timescales of each
 * variant, from its initialization segment, and the decode time offset of each playback session.
 */
internal class TfdtSessions {
    private val timescales = ConcurrentHashMap<String, Map<Int, Long>>()
    private val offsets = ConcurrentHashMap<String, Offset>()

    /** An offset of [duration] / [timescale] seconds. */
    data class Offset(val duration: Long, val timescale: Long)

    fun timescales(variant: String): Map<Int, Long>? = timescales[variant]

    fun putTimescales(variant: String, trackTimescales: Map<Int, Long>) {
        if (timescales.size >= MAX_ENTRIES) timescales.clear()
        timescales[variant] = trackTimescales
    }

    fun offset(session: String): Offset? = offsets[session]

    /**
     * Records [offset] for [session] unless one is already known, and returns the offset in effect.
     */
    fun offerOffset(session: String, offset: Offset): Offset {
        if (offsets.size >= MAX_ENTRIES) offsets.clear()
        return offsets.putIfAbsent(session, offset) ?: offset
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}

/**
 * Passes an fMP4 HLS segment through unchanged, except for the base media decode time (tfdt) of each
 * track fragment.
 *
 * Servers without the fix for jellyfin/jellyfin#18049 write the audio encoder's priming delay into
 * the first segment of a transcode as a negative tfdt. The field is unsigned and media3 rejects it
 * ("Top bit not zero"), so a transcode started from the beginning fails to play. The first negative
 * value seen in a session becomes an offset that is added to every track of every later segment in
 * that session, which is what ffmpeg's `-avoid_negative_ts make_non_negative` does on the server:
 * all tracks move together, so audio and video stay in sync and timestamps never jump. Sessions
 * without a negative value are passed through untouched.
 *
 * Only moov and moof boxes are buffered; everything else, including the media data, is streamed.
 */
@Suppress("MagicNumber") // Box layout offsets of ISO/IEC 14496-12
internal class Fmp4TfdtRewriter(
    private val source: ByteReader,
    private val key: HlsSegmentKey,
    private val sessions: TfdtSessions,
) {
    private var pending = EMPTY
    private var pendingPosition = 0
    private var passthroughRemaining = 0L
    private var ended = false

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (pendingPosition < pending.size) {
                val count = minOf(length, pending.size - pendingPosition)
                pending.copyInto(buffer, offset, pendingPosition, pendingPosition + count)
                pendingPosition += count
                return count
            }
            if (passthroughRemaining != 0L) {
                val count = when (passthroughRemaining) {
                    UNBOUNDED -> length
                    else -> minOf(length.toLong(), passthroughRemaining).toInt()
                }
                val read = source.read(buffer, offset, count)
                if (read == C.RESULT_END_OF_INPUT) {
                    passthroughRemaining = 0
                    ended = true
                    return read
                }
                if (passthroughRemaining != UNBOUNDED) passthroughRemaining -= read
                return read
            }
            if (ended) return C.RESULT_END_OF_INPUT
            startNextBox()
        }
    }

    private fun startNextBox() {
        pendingPosition = 0
        var header = readFully(BOX_HEADER_SIZE)
        if (header.size < BOX_HEADER_SIZE) {
            pending = header
            ended = true
            return
        }
        var size = readUInt32(header, 0)
        val type = String(header, 4, 4, Charsets.US_ASCII)
        if (size == 1L) {
            val largeSize = readFully(LARGE_SIZE_BYTES)
            header += largeSize
            if (largeSize.size < LARGE_SIZE_BYTES) {
                pending = header
                ended = true
                return
            }
            size = readInt64(largeSize, 0)
        }
        // A size of 0 means the box runs to the end; a size smaller than its header is malformed,
        // so stop interpreting and pass the rest through.
        val bodySize = if (size == 0L || size < header.size) UNBOUNDED else size - header.size

        if (type in BUFFERED_BOX_TYPES && bodySize in 0..MAX_BUFFERED_BOX_SIZE) {
            val body = readFully(bodySize.toInt())
            val box = header + body
            if (body.size.toLong() == bodySize) {
                if (type == "moov") recordTimescales(box, header.size) else fixDecodeTimes(box, header.size)
            } else {
                ended = true
            }
            pending = box
        } else {
            pending = header
            passthroughRemaining = bodySize
        }
    }

    private fun recordTimescales(moov: ByteArray, contentStart: Int) {
        val trackTimescales = HashMap<Int, Long>()
        forEachChild(moov, contentStart, moov.size) { type, start, end ->
            if (type != "trak") return@forEachChild
            var trackId: Int? = null
            var timescale: Long? = null
            forEachChild(moov, start, end) { childType, childStart, childEnd ->
                when (childType) {
                    "tkhd" -> trackId = readVersionedHeaderField(moov, childStart, childEnd)?.toInt()
                    "mdia" -> forEachChild(moov, childStart, childEnd) { mdiaType, mdiaStart, mdiaEnd ->
                        if (mdiaType == "mdhd") timescale = readVersionedHeaderField(moov, mdiaStart, mdiaEnd)
                    }
                }
            }
            val id = trackId
            val scale = timescale
            if (id != null && scale != null && scale > 0) trackTimescales[id] = scale
        }
        if (trackTimescales.isNotEmpty()) sessions.putTimescales(key.variant, trackTimescales)
    }

    private fun fixDecodeTimes(moof: ByteArray, contentStart: Int) {
        val times = ArrayList<FragmentTime>()
        forEachChild(moof, contentStart, moof.size) { type, start, end ->
            if (type != "traf") return@forEachChild
            var trackId: Int? = null
            var time: FragmentTime? = null
            forEachChild(moof, start, end) { childType, childStart, childEnd ->
                when (childType) {
                    "tfhd" -> if (childStart + 8 <= childEnd) trackId = readUInt32(moof, childStart + 4).toInt()
                    "tfdt" -> time = readDecodeTime(moof, childStart, childEnd)
                }
            }
            val id = trackId
            time?.takeIf { id != null }?.let { times += it.copy(trackId = id!!) }
        }
        if (times.isEmpty()) return

        val timescales = sessions.timescales(key.variant)
        if (timescales == null) {
            // Without the initialization segment the offset cannot be converted between tracks,
            // so at least make the segment readable.
            times.filter { time -> time.value < 0 }.forEach { time -> write(moof, time, 0) }
            return
        }

        val offset = sessions.offset(key.session) ?: newOffset(moof, times, timescales) ?: return
        for (time in times) {
            val timescale = timescales[time.trackId] ?: continue
            val shift = ceilDiv(offset.duration * timescale, offset.timescale)
            write(moof, time, (time.value + shift).coerceAtLeast(0))
        }
    }

    /**
     * The offset that makes the most negative decode time in this fragment zero, recorded for the
     * session, or null when nothing is negative.
     */
    private fun newOffset(
        moof: ByteArray,
        times: List<FragmentTime>,
        timescales: Map<Int, Long>,
    ): TfdtSessions.Offset? {
        val earliest = times
            .filter { time -> time.value < 0 && (timescales[time.trackId] ?: 0) > 0 }
            .maxByOrNull { time -> -time.value.toDouble() / timescales.getValue(time.trackId) }
            ?: return null
        val timescale = timescales.getValue(earliest.trackId)
        if (-earliest.value > MAX_OFFSET_SECONDS * timescale) {
            // Far larger than any encoder delay, so not something to shift the whole session by.
            times.filter { time -> time.value < 0 }.forEach { time -> write(moof, time, 0) }
            return null
        }
        return sessions.offerOffset(key.session, TfdtSessions.Offset(-earliest.value, timescale))
    }

    private fun readFully(length: Int): ByteArray {
        val data = ByteArray(length)
        var position = 0
        while (position < length) {
            val read = source.read(data, position, length - position)
            if (read == C.RESULT_END_OF_INPUT) return data.copyOf(position)
            position += read
        }
        return data
    }

    private data class FragmentTime(val trackId: Int, val valueOffset: Int, val is64Bit: Boolean, val value: Long)

    private companion object {
        val EMPTY = ByteArray(0)
        const val BOX_HEADER_SIZE = 8
        const val LARGE_SIZE_BYTES = 8
        const val UNBOUNDED = -1L
        const val MAX_BUFFERED_BOX_SIZE = 16L * 1024 * 1024
        val BUFFERED_BOX_TYPES = setOf("moov", "moof")
        const val MAX_OFFSET_SECONDS = 10L
        const val MAX_UINT32 = 0xFFFFFFFFL

        /**
         * Calls [block] with the type, content start and end of each child box between [start] and [end].
         */
        fun forEachChild(data: ByteArray, start: Int, end: Int, block: (String, Int, Int) -> Unit) {
            var position = start
            while (position + BOX_HEADER_SIZE <= end) {
                var size = readUInt32(data, position)
                var headerSize = BOX_HEADER_SIZE
                when (size) {
                    1L -> {
                        if (position + BOX_HEADER_SIZE + LARGE_SIZE_BYTES > end) return
                        size = readInt64(data, position + BOX_HEADER_SIZE)
                        headerSize += LARGE_SIZE_BYTES
                    }
                    0L -> size = (end - position).toLong()
                }
                if (size < headerSize || position + size > end) return
                val type = String(data, position + 4, 4, Charsets.US_ASCII)
                block(type, position + headerSize, (position + size).toInt())
                position += size.toInt()
            }
        }

        /**
         * The field after the creation and modification times of a tkhd (track ID) or mdhd (timescale)
         * box, whose layout depends on the box version.
         */
        fun readVersionedHeaderField(data: ByteArray, start: Int, end: Int): Long? {
            if (start >= end) return null
            val fieldOffset = start + if (data[start].toInt() == 1) 20 else 12
            return if (fieldOffset + 4 <= end) readUInt32(data, fieldOffset) else null
        }

        fun readDecodeTime(data: ByteArray, start: Int, end: Int): FragmentTime? {
            if (start >= end) return null
            val is64Bit = data[start].toInt() == 1
            val valueOffset = start + 4
            return when {
                is64Bit && valueOffset + 8 <= end -> FragmentTime(0, valueOffset, true, readInt64(data, valueOffset))
                !is64Bit && valueOffset + 4 <= end -> FragmentTime(0, valueOffset, false, readUInt32(data, valueOffset))
                else -> null
            }
        }

        fun write(data: ByteArray, time: FragmentTime, value: Long) {
            when {
                time.is64Bit -> writeInt64(data, time.valueOffset, value)
                value <= MAX_UINT32 -> writeUInt32(data, time.valueOffset, value)
            }
        }

        fun ceilDiv(dividend: Long, divisor: Long): Long = (dividend + divisor - 1) / divisor

        fun readUInt32(data: ByteArray, offset: Int): Long =
            (data[offset].toLong() and 0xFF shl 24) or
                (data[offset + 1].toLong() and 0xFF shl 16) or
                (data[offset + 2].toLong() and 0xFF shl 8) or
                (data[offset + 3].toLong() and 0xFF)

        fun readInt64(data: ByteArray, offset: Int): Long =
            (readUInt32(data, offset) shl 32) or readUInt32(data, offset + 4)

        fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 4) data[offset + i] = (value shr (24 - 8 * i)).toByte()
        }

        fun writeInt64(data: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) data[offset + i] = (value shr (56 - 8 * i)).toByte()
        }
    }
}
