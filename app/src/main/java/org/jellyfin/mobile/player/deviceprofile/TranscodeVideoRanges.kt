package org.jellyfin.mobile.player.deviceprofile

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

// Display.HdrCapabilities.HDR_TYPE_* (API 24), repeated here so the lookup below compiles on API 23.
internal const val HDR_TYPE_DOLBY_VISION = 1
internal const val HDR_TYPE_HDR10 = 2
internal const val HDR_TYPE_HLG = 3
internal const val HDR_TYPE_HDR10_PLUS = 4

private const val DOLBY_VISION_PROFILE_5 = 5
private const val DOLBY_VISION_PROFILE_7 = 7
private const val DOLBY_VISION_PROFILE_8 = 8
private const val DOLBY_VISION_PROFILE_10 = 10

/**
 * The server's VideoRangeType names this device can present in a stream the server produces.
 *
 * HDR types only when the display reports them, so the server tone-maps instead of copying HDR
 * video for an SDR screen. Dolby Vision types only for the profiles a decoder advertises for
 * [codec], otherwise the server removes the Dolby Vision metadata from a compatible base layer.
 */
internal fun transcodeVideoRangeTypes(
    codec: String,
    displayHdrTypes: Set<Int>,
    dolbyVisionProfiles: Set<Int>,
): List<String> = buildList {
    add("SDR")
    if (HDR_TYPE_HDR10 in displayHdrTypes) add("HDR10")
    if (HDR_TYPE_HDR10_PLUS in displayHdrTypes) add("HDR10Plus")
    if (HDR_TYPE_HLG in displayHdrTypes) add("HLG")

    when (codec) {
        "hevc" -> {
            if (DOLBY_VISION_PROFILE_5 in dolbyVisionProfiles) add("DOVI")
            if (DOLBY_VISION_PROFILE_7 in dolbyVisionProfiles) addAll(listOf("DOVIWithEL", "DOVIWithELHDR10Plus"))
            if (DOLBY_VISION_PROFILE_8 in dolbyVisionProfiles) {
                addAll(listOf("DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus"))
            }
        }
        "av1" -> {
            if (DOLBY_VISION_PROFILE_10 in dolbyVisionProfiles) {
                addAll(listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR"))
            }
        }
    }
}

/**
 * The HDR types the default display reports, as Display.HdrCapabilities.HDR_TYPE_* values.
 */
internal fun Context.displayHdrTypes(): Set<Int> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptySet()

    val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()

    @Suppress("DEPRECATION")
    val types = display.hdrCapabilities?.supportedHdrTypes?.toMutableSet() ?: mutableSetOf()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        types += display.mode.supportedHdrTypes.toSet()
    }
    return types
}
