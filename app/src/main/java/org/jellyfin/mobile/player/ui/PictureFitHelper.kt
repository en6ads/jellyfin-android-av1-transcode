package org.jellyfin.mobile.player.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import timber.log.Timber
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.media3.ui.R as Media3R

/**
 * Fit to picture: zooms the video so the picture, not the frame around it, fills the screen, and never crops
 * the picture - a 2.39:1 film in a 16:9 frame fills a 20:9 phone, a 4:3 film pillarboxed in 16:9 fills its
 * height. The decoded frames go straight to the screen, so a few times a second a small copy of the frame
 * on show is taken and its black bars are measured ([PictureMeasurement], [PictureFitDecision]).
 *
 * The copy is of the frame as decoded, whatever the zoom, so zooming never feeds back into the measurement.
 * Measuring what is already on screen means a shape that widens is cropped for up to [INTERVAL_MS] before
 * the zoom widens with it.
 */
@RequiresApi(Build.VERSION_CODES.N)
class PictureFitHelper(
    private val playerView: PlayerView,
    private val isEnabled: () -> Boolean,
    private val isInPictureInPicture: () -> Boolean,
) {
    private val contentFrame: View = playerView.findViewById(Media3R.id.exo_content_frame)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val copyThread = HandlerThread("PictureFit").apply { start() }
    private val copyHandler = Handler(copyThread.looper)
    private val decision = PictureFitDecision()
    private var shown = PictureArea.FULL_FRAME
    private var bitmap: Bitmap? = null
    private var copying = false
    private var copyStartedMs = 0L
    private var mediaItemIndex = -1
    private var videoWidth = 0
    private var videoHeight = 0
    private var failuresLogged = false
    private var running = false
    private var paused = false

    private val tick = object : Runnable {
        override fun run() {
            sample()
            mainHandler.postDelayed(this, INTERVAL_MS)
        }
    }

    private val relayout = View.OnLayoutChangeListener { view, _, _, _, _, oldLeft, oldTop, oldRight, oldBottom ->
        if (view.width != oldRight - oldLeft || view.height != oldBottom - oldTop) {
            mainHandler.post { apply(shown, force = true) }
        }
    }

    fun start() {
        if (running) return
        running = true
        contentFrame.addOnLayoutChangeListener(relayout)
        mainHandler.post(tick)
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(tick)
        contentFrame.removeOnLayoutChangeListener(relayout)
        apply(PictureArea.FULL_FRAME)
        copyThread.quitSafely()
    }

    /** The setting, Picture-in-Picture or the zoom mode changed: show the whole frame now if fitting stopped. */
    fun refresh() {
        if (!fitting()) pause()
    }

    private fun fitting() =
        isEnabled() && !isInPictureInPicture() && playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT

    /** Shows the whole frame and forgets the area, so fitting again starts afresh rather than from a stale area. */
    private fun pause() {
        if (!paused) {
            paused = true
            Timber.d("Fit to picture: paused (setting off, zoomed or picture-in-picture)")
        }
        decision.reset()
        apply(PictureArea.FULL_FRAME)
    }

    @Suppress("ReturnCount")
    private fun sample() {
        val player = playerView.player ?: return
        if (!fitting()) {
            pause()
            return
        }
        if (paused) {
            paused = false
            Timber.d("Fit to picture: resumed")
        }

        restartOnNewVideo(player)
        val size = player.videoSize
        val surfaceView = playerView.videoSurfaceView as? SurfaceView ?: return
        val showing = size.width > 0 && size.height > 0 && player.playbackState == Player.STATE_READY
        abandonLostCopy()
        if (copying || !showing || !surfaceView.holder.surface.isValid) return

        val copyHeight = (COPY_WIDTH * size.height / (size.width * size.pixelWidthHeightRatio)).roundToInt()
            .coerceIn(MIN_COPY_HEIGHT, COPY_WIDTH)
        val target = bitmap?.takeIf { it.height == copyHeight }
            ?: createBitmap(COPY_WIDTH, copyHeight).also {
                bitmap?.recycle()
                bitmap = it
            }
        val positionMs = player.currentPosition
        copying = true
        copyStartedMs = SystemClock.uptimeMillis()
        try {
            PixelCopy.request(surfaceView, target, { result -> onCopied(result, target, positionMs) }, copyHandler)
        } catch (e: IllegalArgumentException) {
            copying = false
            logFailure("copy refused: ${e.message}")
        } catch (e: IllegalStateException) {
            copying = false // never left waiting for a copy that was not started
            logFailure("copy refused: ${e.message}")
        }
    }

    /** A copy that never reported back: gives up on it, and on its bitmap, which it may still write to. */
    private fun abandonLostCopy() {
        if (!copying || SystemClock.uptimeMillis() - copyStartedMs <= COPY_TIMEOUT_MS) return
        Timber.w("Fit to picture: a frame copy never finished; starting again")
        copying = false
        bitmap = null
    }

    /** A new item, or a new shape (a stream switched to another resolution): start again from the whole frame. */
    private fun restartOnNewVideo(player: Player) {
        val size = player.videoSize
        val sameVideo = size.width == videoWidth && size.height == videoHeight
        if (player.currentMediaItemIndex == mediaItemIndex && sameVideo) return
        mediaItemIndex = player.currentMediaItemIndex
        videoWidth = size.width
        videoHeight = size.height
        decision.reset()
        apply(PictureArea.FULL_FRAME)
    }

    /** On the copy thread: measures the copy, then decides on the main thread. */
    private fun onCopied(result: Int, copy: Bitmap, positionMs: Long) {
        val content = if (result == PixelCopy.SUCCESS) {
            val pixels = IntArray(copy.width * copy.height)
            copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
            PictureMeasurement.measure(pixels, copy.width, copy.height)
        } else {
            null
        }
        mainHandler.post {
            copying = false
            if (result != PixelCopy.SUCCESS) {
                logFailure("copy failed ($result)")
                return@post
            }
            if (running && fitting()) {
                // What is shown always follows the decision, not only its changes.
                decision.step(positionMs, content)
                apply(decision.decided)
            }
        }
    }

    /** Scales and moves the video so [area] of the frame fills as much of the view as it can, uncropped. */
    private fun apply(area: PictureArea, force: Boolean = false) {
        if (area == shown && !force) return
        if (area != shown) Timber.d("Fit to picture: %s", if (area.isFullFrame) "whole frame" else area)
        shown = area
        val frameWidth = contentFrame.width.toFloat()
        val frameHeight = contentFrame.height.toFloat()
        val viewWidth = (playerView.width - playerView.paddingLeft - playerView.paddingRight).toFloat()
        val viewHeight = (playerView.height - playerView.paddingTop - playerView.paddingBottom).toFloat()
        val laidOut = minOf(frameWidth, frameHeight, viewWidth, viewHeight) > 0f
        if (area.isFullFrame || !laidOut) {
            contentFrame.scaleX = 1f
            contentFrame.scaleY = 1f
            contentFrame.translationX = 0f
            contentFrame.translationY = 0f
            return
        }

        // The frame is laid out to fit the view; the picture's part of it is scaled up to fit the view instead,
        // and its centre brought to the view's centre.
        val scale = min(viewWidth / (area.width * frameWidth), viewHeight / (area.height * frameHeight))
            .coerceAtLeast(min(viewWidth / frameWidth, viewHeight / frameHeight))
        contentFrame.pivotX = frameWidth / 2
        contentFrame.pivotY = frameHeight / 2
        contentFrame.scaleX = scale
        contentFrame.scaleY = scale
        contentFrame.translationX = (frameWidth / 2 - (area.left + area.right) / 2 * frameWidth) * scale
        contentFrame.translationY = (frameHeight / 2 - (area.top + area.bottom) / 2 * frameHeight) * scale
    }

    private fun logFailure(message: String) {
        if (failuresLogged) return
        failuresLogged = true
        Timber.w("Fit to picture: %s - the picture cannot be measured on this device or stream", message)
    }

    companion object {
        const val INTERVAL_MS = 250L
        private const val COPY_WIDTH = 480
        private const val MIN_COPY_HEIGHT = 48
        private const val COPY_TIMEOUT_MS = 2000L
    }
}
