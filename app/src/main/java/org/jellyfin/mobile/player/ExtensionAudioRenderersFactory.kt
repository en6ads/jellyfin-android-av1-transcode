package org.jellyfin.mobile.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * A [DefaultRenderersFactory] that can prefer the extension renderers, such as FFmpeg, for audio alone.
 *
 * [setExtensionRendererMode] applies to video as well, and preferring the extensions there would put
 * the video on a software decoder.
 */
@UnstableApi
class ExtensionAudioRenderersFactory(
    context: Context,
    private val preferExtensionAudio: Boolean,
) : DefaultRenderersFactory(context) {
    @Suppress("LongParameterList") // Defined by DefaultRenderersFactory
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            if (preferExtensionAudio) EXTENSION_RENDERER_MODE_PREFER else extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
