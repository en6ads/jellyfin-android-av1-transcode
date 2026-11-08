package org.jellyfin.mobile.player.ui

import kotlin.math.max
import kotlin.math.min

/**
 * The part of the video frame that holds picture, as fractions of the frame's width and height, so it is the same
 * whatever the frame's resolution or pixel aspect ratio.
 */
data class PictureArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFullFrame: Boolean get() = this == FULL_FRAME

    fun contains(other: PictureArea, tolerance: Float = 0f): Boolean =
        other.left >= left - tolerance && other.top >= top - tolerance &&
            other.right <= right + tolerance && other.bottom <= bottom + tolerance

    fun sameAs(other: PictureArea, tolerance: Float): Boolean =
        contains(other, tolerance) && other.contains(this, tolerance)

    fun union(other: PictureArea) = PictureArea(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )

    override fun toString() = "%.3f,%.3f-%.3f,%.3f".format(left, top, right, bottom)

    companion object {
        val FULL_FRAME = PictureArea(0f, 0f, 1f, 1f)
    }
}

/**
 * Finds the black bars in a small copy of a frame (ARGB, as [android.graphics.Bitmap.getPixels] gives it).
 *
 * A bar is coded black: most of it within a few values of black and nothing above a few more. Dark picture
 * sits higher and always has something brighter in it (grain, a highlight, a star), so a row or column with
 * two samples above the bar's ceiling is picture. Each of the four sides is found on its own.
 */
object PictureMeasurement {
    // In the copy's full-range RGB, the video's black (16) is 0; bars measured in the Windows player sit within
    // 4 of its 219 steps. The copy is scaled down, which averages compression noise away.
    private const val BAR_TYPICAL = 4
    private const val BAR_MAXIMUM = 10
    private const val MIN_PICTURE_FRACTION = 10 // a picture narrower than a tenth of the frame is a fade or a speck

    // BT.709 luma, in 256ths.
    private const val RED_WEIGHT = 54
    private const val GREEN_WEIGHT = 183
    private const val BLUE_WEIGHT = 19
    private const val WEIGHT_BITS = 8
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL = 0xff

    /** Returns the area holding picture, or null when the frame is (nearly) all black and says nothing about it. */
    @Suppress("CyclomaticComplexMethod")
    fun measure(pixels: IntArray, width: Int, height: Int): PictureArea? {
        fun luma(x: Int, y: Int): Int {
            val pixel = pixels[y * width + x]
            val red = pixel shr RED_SHIFT and CHANNEL
            val green = pixel shr GREEN_SHIFT and CHANNEL
            val blue = pixel and CHANNEL
            return (red * RED_WEIGHT + green * GREEN_WEIGHT + blue * BLUE_WEIGHT) shr WEIGHT_BITS
        }

        fun isBar(x0: Int, y0: Int, dx: Int, dy: Int, count: Int): Boolean {
            var nearBlack = 0
            var above = 0
            var x = x0
            var y = y0
            repeat(count) {
                val value = luma(x, y)
                if (value > BAR_MAXIMUM && ++above >= 2) return false
                if (value <= BAR_TYPICAL) nearBlack++
                x += dx
                y += dy
            }
            return nearBlack * 2 >= count
        }

        fun rowHasPicture(y: Int, x0: Int, x1: Int) = !isBar(x0, y, 1, 0, x1 - x0 + 1)
        fun columnHasPicture(x: Int, y0: Int, y1: Int) = !isBar(x, y0, 0, 1, y1 - y0 + 1)

        var top = 0
        while (top < height && !rowHasPicture(top, 0, width - 1)) top++
        if (top >= height) return null
        var bottom = height - 1
        while (bottom > top && !rowHasPicture(bottom, 0, width - 1)) bottom--

        // Left and right within the rows that hold picture, so a letterbox does not hide side bars.
        var left = 0
        while (left < width && !columnHasPicture(left, top, bottom)) left++
        if (left >= width) return null
        var right = width - 1
        while (right > left && !columnHasPicture(right, top, bottom)) right--

        val tooNarrow = (right - left + 1) * MIN_PICTURE_FRACTION < width
        val tooLow = (bottom - top + 1) * MIN_PICTURE_FRACTION < height
        if (tooNarrow || tooLow) return null

        return PictureArea(
            left / width.toFloat(),
            top / height.toFloat(),
            (right + 1) / width.toFloat(),
            (bottom + 1) / height.toFloat(),
        )
    }
}

/**
 * Decides the area to show from measurements, as the Windows player does: picture found outside the area
 * zooms out at once (the picture is never cropped), and the area only zooms in when measurements agree for
 * half a second. Bars on all four sides are an intertitle, an iris or a vignette, never a reason to zoom in,
 * and zooming in by less than 2% of the frame gains nothing visible.
 */
class PictureFitDecision {
    var decided: PictureArea = PictureArea.FULL_FRAME
        private set
    private var pending: PictureArea? = null
    private var pendingSinceMs = 0L

    fun reset() {
        decided = PictureArea.FULL_FRAME
        pending = null
    }

    /** Advances the decision by one measurement taken at [positionMs]; returns whether [decided] changed. */
    fun step(positionMs: Long, content: PictureArea?): Boolean {
        if (content == null) return false
        val before = decided
        val pending = pending
        if (!decided.contains(content)) {
            decided = withMargin(snap(decided.union(content))) // picture outside the area: zoom out now
            this.pending = null
        } else {
            val candidate = withMargin(snap(content))
            when {
                !worthZoomingIn(candidate) -> this.pending = null
                pending != null && pending.sameAs(candidate, SAME_TOLERANCE) && positionMs >= pendingSinceMs -> {
                    // Agreeing measurements widen the pending area to the largest of them, so zooming in never cuts off
                    // a frame measured in between.
                    val widened = pending.union(candidate)
                    if (positionMs - pendingSinceMs >= ZOOM_IN_CONFIRMATION_MS) {
                        decided = widened
                        this.pending = null
                    } else {
                        this.pending = widened
                    }
                }
                else -> {
                    this.pending = candidate
                    pendingSinceMs = positionMs
                }
            }
        }
        return decided != before
    }

    private fun worthZoomingIn(candidate: PictureArea): Boolean {
        val sideBars = candidate.left > 0f || candidate.right < 1f
        val topBars = candidate.top > 0f || candidate.bottom < 1f
        if (sideBars && topBars) return false
        return candidate.height < decided.height - MIN_ZOOM_IN || candidate.width < decided.width - MIN_ZOOM_IN
    }

    companion object {
        const val ZOOM_IN_CONFIRMATION_MS = 500L
        private const val MIN_ZOOM_IN = 0.02f
        private const val SAME_TOLERANCE = 0.006f
        private const val SNAP = 0.005f // a bar thinner than this is none: the copy is coarse, and soft edges waver
        private const val MARGIN = 0.005f // room for an edge that creeps outward, and for the coarse copy

        /** Treats a bar thinner than [SNAP] as none. */
        fun snap(area: PictureArea) = PictureArea(
            if (area.left <= SNAP) 0f else area.left,
            if (area.top <= SNAP) 0f else area.top,
            if (area.right >= 1f - SNAP) 1f else area.right,
            if (area.bottom >= 1f - SNAP) 1f else area.bottom,
        )

        /** Leaves a little more room on each side that has a bar. */
        fun withMargin(area: PictureArea) = PictureArea(
            max(0f, area.left - if (area.left > 0f) MARGIN else 0f),
            max(0f, area.top - if (area.top > 0f) MARGIN else 0f),
            min(1f, area.right + if (area.right < 1f) MARGIN else 0f),
            min(1f, area.bottom + if (area.bottom < 1f) MARGIN else 0f),
        )
    }
}
