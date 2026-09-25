package org.jellyfin.mobile.player.qualityoptions

import android.content.Context
import org.jellyfin.mobile.R
import java.util.Locale

private const val BITRATE_MEGA_BIT = 1_000_000
private const val BITRATE_KILO_BIT = 1_000

/**
 * The label for a quality option, e.g. "1080p - 10 Mbps", or "Auto" for the zero-bitrate option.
 */
fun QualityOption.getLabel(context: Context): String = when (bitrate) {
    0 -> context.getString(R.string.menu_item_auto)
    else -> "${maxHeight}p - ${formatBitrate(bitrate.toDouble())}"
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
