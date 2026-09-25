package org.jellyfin.mobile.player.hls

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Creates HLS media sources with [hlsFactory] and everything else with [defaultFactory].
 *
 * DefaultMediaSourceFactory builds its own HLS factory and offers no way to configure it.
 * An HLS item with subtitle configurations still goes to [defaultFactory], which merges them in.
 */
@UnstableApi
class HlsRoutingMediaSourceFactory(
    private val defaultFactory: MediaSource.Factory,
    private val hlsFactory: MediaSource.Factory,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hlsFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hlsFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val localConfiguration = requireNotNull(mediaItem.localConfiguration)
        val contentType = Util.inferContentTypeForUriAndMimeType(localConfiguration.uri, localConfiguration.mimeType)
        val factory = when {
            contentType == C.CONTENT_TYPE_HLS && localConfiguration.subtitleConfigurations.isEmpty() -> hlsFactory
            else -> defaultFactory
        }
        return factory.createMediaSource(mediaItem)
    }
}
