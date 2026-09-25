package org.jellyfin.mobile.player.qualityoptions

import android.content.Context
import org.jellyfin.mobile.R
import java.util.Locale

private const val BITRATE_MEGA_BIT = 1_000_000
private const val BITRATE_KILO_BIT = 1_000

/**
 * The user-facing label for a quality option, e.g. "10 Mbps", or "Auto" for the zero-bitrate
 * option. It names no resolution: the server picks that from the bitrate, by its own rules.
 *
 * Shared between the in-player quality menu and the pre-playback quality picker so the two
 * cannot drift apart: the same bitrate must read the same way wherever it is offered, or the
 * picker's "3.0 Mbps" and the menu's "3.0 Mbps" stop being recognisably the same choice.
 */
fun QualityOption.getLabel(context: Context): String = when (bitrate) {
    0 -> context.getString(R.string.menu_item_auto)
    else -> formatBitrate(bitrate.toDouble())
}

fun formatBitrate(bitrate: Double): String {
    val (value, unit) = when {
        bitrate > BITRATE_MEGA_BIT -> bitrate / BITRATE_MEGA_BIT to " Mbps"
        bitrate > BITRATE_KILO_BIT -> bitrate / BITRATE_KILO_BIT to " kbps"
        else -> bitrate to " bps"
    }

    // Remove unnecessary trailing zeros
    val formatted = "%.2f".format(Locale.getDefault(), value).removeSuffix(".00")
    return formatted + unit
}
