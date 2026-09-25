package org.jellyfin.mobile.player.qualityoptions

/**
 * Result of the pre-playback quality prompt.
 */
sealed interface QualityChoice {
    /** Use the web client's quality setting. */
    data object Auto : QualityChoice

    /** Cap the stream at [bitrate] bits per second. */
    data class Capped(val bitrate: Int) : QualityChoice

    /** The prompt was dismissed without choosing; playback should not start. */
    data object Cancelled : QualityChoice
}
