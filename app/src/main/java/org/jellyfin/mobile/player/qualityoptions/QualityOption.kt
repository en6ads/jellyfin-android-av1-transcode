package org.jellyfin.mobile.player.qualityoptions

/**
 * A bitrate the user can cap playback at. The server picks the output resolution for it, so the
 * option is offered only for sources at least [minSourceHeight] tall, where it makes a difference.
 */
data class QualityOption(
    val minSourceHeight: Int,
    val bitrate: Int,
)
