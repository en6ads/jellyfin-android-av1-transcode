package org.jellyfin.mobile.utils.extensions

import android.util.Rational
import org.jellyfin.sdk.model.api.MediaStream

val MediaStream.isLandscape: Boolean
    get() = if (width != null && height != null) width!! >= height!! else true

val MediaStream.aspectRational: Rational?
    get() = if (width != null && height != null) Rational(width!!, height!!) else null

/**
 * The stream's true display aspect ratio (width/height), as reported by the server's own
 * [MediaStream.aspectRatio] string (e.g. "16:9") - unlike [aspectRational], which is derived
 * from the raw coded pixel dimensions, this reflects any pixel-aspect-ratio correction the
 * server already applied, so it's correct for anamorphic sources where the coded frame is
 * squeezed narrower than what actually gets displayed.
 */
val MediaStream.displayAspectRatio: Float?
    get() {
        val parts = aspectRatio?.split(":")?.takeIf { it.size == 2 } ?: return null
        val width = parts[0].toFloatOrNull() ?: return null
        val height = parts[1].toFloatOrNull() ?: return null
        return if (height != 0f) width / height else null
    }
