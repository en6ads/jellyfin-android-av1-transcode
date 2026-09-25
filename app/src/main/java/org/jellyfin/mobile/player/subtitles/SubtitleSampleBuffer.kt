package org.jellyfin.mobile.player.subtitles

/**
 * The parsed cue samples of one sidecar subtitle file, written by the loader and read by the player.
 *
 * Samples are kept for the whole file, so seeking backwards never needs a reload; encoded cues are
 * small, even PGS bitmaps once compressed.
 *
 * @param replacesCues whether each sample replaces the previous one (PGS) rather than adding cues with
 * their own end times (text formats).
 */
internal class SubtitleSampleBuffer(private val replacesCues: Boolean) {
    class Sample(val timeUs: Long, val endTimeUs: Long, val data: ByteArray)

    private val samples = ArrayList<Sample>()

    @Volatile
    var isFinished = false
        private set

    /** Adds a sample. [endTimeUs] is [Long.MAX_VALUE] for a sample that lasts until it is replaced. */
    @Synchronized
    fun add(timeUs: Long, endTimeUs: Long, data: ByteArray) {
        samples += Sample(timeUs, endTimeUs, data)
    }

    fun finish() {
        isFinished = true
    }

    @Synchronized
    fun get(index: Int): Sample? = samples.getOrNull(index)

    @Synchronized
    fun isEmpty(): Boolean = samples.isEmpty()

    /** The time of the last sample, or null when there is none. */
    @Synchronized
    fun lastTimeUs(): Long? = samples.lastOrNull()?.timeUs

    /**
     * The index to read from to show the cues at [positionUs]: for replacing cues the last sample
     * starting at or before it, otherwise the first sample still showing at it.
     */
    @Synchronized
    fun indexForPosition(positionUs: Long): Int = if (replacesCues) {
        samples.indexOfLast { sample -> sample.timeUs <= positionUs }.coerceAtLeast(0)
    } else {
        samples.indexOfFirst { sample -> sample.endTimeUs > positionUs }.takeIf { it >= 0 } ?: samples.size
    }
}
