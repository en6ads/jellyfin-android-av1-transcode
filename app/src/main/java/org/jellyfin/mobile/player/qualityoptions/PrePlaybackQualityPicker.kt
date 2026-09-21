package org.jellyfin.mobile.player.qualityoptions

import android.content.Context
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.mobile.R
import kotlin.coroutines.resume

/**
 * Result of the pre-playback quality prompt.
 */
sealed interface QualityChoice {
    /** Let the server decide, i.e. behave exactly as before this prompt existed. */
    data object Auto : QualityChoice

    /** Cap the stream at [bitrate] bits per second. */
    data class Capped(val bitrate: Int) : QualityChoice

    /** The prompt was dismissed without choosing; playback should not start. */
    data object Cancelled : QualityChoice
}

/**
 * Asks which quality to stream at, before the media source is resolved.
 *
 * The full ladder is offered rather than one narrowed by the source resolution, because at this
 * point nothing has been fetched yet and the item's dimensions are not known - finding them out
 * would cost the extra round trip this prompt exists to avoid. Picking 1080p for a 720p source
 * is harmless: the bitrate cap still applies and the server will not upscale.
 *
 * Suspends until the user chooses or dismisses. Cancelling the surrounding coroutine dismisses
 * the dialog, so a fragment torn down mid-prompt does not leak it.
 */
suspend fun Context.askPlaybackQuality(
    qualityOptionsProvider: QualityOptionsProvider,
    preselectedBitrate: Int,
): QualityChoice = suspendCancellableCoroutine { continuation ->
    val options = qualityOptionsProvider.getApplicableQualityOptions(
        videoWidth = MAX_LADDER_WIDTH,
        videoHeight = MAX_LADDER_HEIGHT,
    )
    val labels = options.map { option -> option.getLabel(this) }.toTypedArray()

    // Fall back to the "auto" entry when the remembered bitrate is no longer in the ladder.
    val checkedItem = options
        .indexOfFirst { option -> option.bitrate == preselectedBitrate }
        .takeIf { index -> index >= 0 }
        ?: options.lastIndex

    var chosenIndex = checkedItem

    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.pre_playback_quality_title)
        .setSingleChoiceItems(labels, checkedItem) { _, which -> chosenIndex = which }
        .setPositiveButton(R.string.pre_playback_quality_play) { _, _ ->
            val bitrate = options[chosenIndex].bitrate
            continuation.resume(if (bitrate == 0) QualityChoice.Auto else QualityChoice.Capped(bitrate))
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            continuation.resume(QualityChoice.Cancelled)
        }
        .setOnCancelListener {
            continuation.resume(QualityChoice.Cancelled)
        }
        .create()

    continuation.invokeOnCancellation { dialog.dismiss() }
    dialog.show()
}

private const val MAX_LADDER_WIDTH = 3840
private const val MAX_LADDER_HEIGHT = 2160
