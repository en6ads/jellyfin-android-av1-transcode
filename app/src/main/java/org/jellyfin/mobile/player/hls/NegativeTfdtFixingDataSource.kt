package org.jellyfin.mobile.player.hls

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Repairs negative decode times in Jellyfin fMP4 HLS segments before media3 parses them, so transcodes
 * from servers without the fix for jellyfin/jellyfin#18049 play from the beginning. See
 * [Fmp4TfdtRewriter]. Anything that is not a Jellyfin HLS fMP4 segment is passed through untouched.
 */
@UnstableApi
class NegativeTfdtFixingDataSource private constructor(
    private val upstream: DataSource,
    private val sessions: TfdtSessions,
) : DataSource {
    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        private val sessions = TfdtSessions()

        override fun createDataSource(): DataSource =
            NegativeTfdtFixingDataSource(upstreamFactory.createDataSource(), sessions)
    }

    private var rewriter: Fmp4TfdtRewriter? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        // A load resumed part way through a segment is already past the boxes that need fixing
        rewriter = HlsSegmentKey.of(dataSpec.uri.toString())
            ?.takeIf { dataSpec.position == 0L }
            ?.let { key -> Fmp4TfdtRewriter(upstream::read, key, sessions) }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        rewriter?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        rewriter = null
        upstream.close()
    }
}
