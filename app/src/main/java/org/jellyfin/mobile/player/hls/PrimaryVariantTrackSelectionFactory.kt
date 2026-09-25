package org.jellyfin.mobile.player.hls

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter

/**
 * Keeps Jellyfin HLS video on the server's primary variant instead of adapting between variants.
 *
 * Jellyfin has no bitrate ladder. Its master playlist can list compatibility variants next to the
 * primary one, such as SDR re-encodes of an HDR stream, at the same bandwidth so that players choose
 * by other attributes. Adapting between them switches the picture between HDR and SDR, starts a
 * separate transcode on the server for every switch, and, without a bandwidth estimate yet, starts on
 * the last of them. When every candidate video track has the same bitrate, the first one the device
 * can play is selected instead. Genuine bitrate ladders are left to [delegate].
 */
@UnstableApi
class PrimaryVariantTrackSelectionFactory(
    private val delegate: ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory(),
) : ExoTrackSelection.Factory {
    override fun createTrackSelections(
        definitions: Array<out ExoTrackSelection.Definition?>,
        bandwidthMeter: BandwidthMeter,
        mediaPeriodId: MediaSource.MediaPeriodId,
        timeline: Timeline,
    ): Array<ExoTrackSelection?> {
        val pinned = definitions.map { definition ->
            definition?.takeIf(::isSameBitrateVideoChoice)?.let { FixedTrackSelection(it.group, it.tracks.min()) }
        }
        val delegated = delegate.createTrackSelections(
            Array(definitions.size) { index -> definitions[index].takeIf { pinned[index] == null } },
            bandwidthMeter,
            mediaPeriodId,
            timeline,
        )
        return Array(definitions.size) { index -> pinned[index] ?: delegated[index] }
    }
}

/**
 * Whether [definition] offers a choice between video tracks that only differ in something other than
 * bitrate.
 */
@UnstableApi
internal fun isSameBitrateVideoChoice(definition: ExoTrackSelection.Definition): Boolean {
    if (definition.group.type != C.TRACK_TYPE_VIDEO || definition.tracks.size < 2) return false
    return definition.tracks.map { track -> definition.group.getFormat(track).bitrate }.distinct().size == 1
}
