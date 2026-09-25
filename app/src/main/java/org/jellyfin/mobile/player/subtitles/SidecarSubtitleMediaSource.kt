package org.jellyfin.mobile.player.subtitles

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

/**
 * A subtitle file delivered next to the media, such as one the server extracts from the container.
 *
 * Unlike media3's default sidecar handling, which starts downloading every subtitle file as soon as
 * playback is prepared and holds preparation until each server has answered, this source is ready at
 * once and only downloads the file when its track is selected. The server may need minutes to extract
 * an embedded subtitle from a large file; playback no longer waits for that, and the cues appear once
 * the file arrives.
 */
@UnstableApi
class SidecarSubtitleMediaSource(
    configuration: MediaItem.SubtitleConfiguration,
    private val dataSourceFactory: DataSource.Factory,
) : BaseMediaSource() {
    private val uri: Uri = configuration.uri
    private val sourceFormat = Format.Builder()
        .setSampleMimeType(configuration.mimeType)
        .setLanguage(configuration.language)
        .build()
    private val format = Format.Builder()
        .setId(configuration.id)
        .setLabel(configuration.label)
        .setLanguage(configuration.language)
        .setSelectionFlags(configuration.selectionFlags)
        .setRoleFlags(configuration.roleFlags)
        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
        .setCodecs(configuration.mimeType)
        .setCueReplacementBehavior(PARSER_FACTORY.getCueReplacementBehavior(sourceFormat))
        .build()
    private val mediaItem = MediaItem.Builder().setUri(uri).build()

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        refreshSourceInfo(SinglePeriodTimeline(C.TIME_UNSET, true, false, false, null, mediaItem))
    }

    override fun maybeThrowSourceInfoRefreshError() = Unit

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        SidecarSubtitleMediaPeriod()

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as SidecarSubtitleMediaPeriod).release()
    }

    override fun releaseSourceInternal() = Unit

    @Suppress("TooManyFunctions") // Required by MediaPeriod and Loader.Callback
    private inner class SidecarSubtitleMediaPeriod : MediaPeriod, Loader.Callback<SubtitleLoadable> {
        private val trackGroups = TrackGroupArray(TrackGroup(format))
        private val samples = SubtitleSampleBuffer(
            replacesCues = format.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE,
        )
        private val loader = Loader("SidecarSubtitleLoader")
        private var stream: SubtitleSampleStream? = null
        private var loadStarted = false

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            callback.onPrepared(this)
        }

        override fun maybeThrowPrepareError() = Unit

        override fun getTrackGroups(): TrackGroupArray = trackGroups

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            for (i in selections.indices) {
                if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                    streams[i] = null
                    stream = null
                }
                if (streams[i] == null && selections[i] != null) {
                    val newStream = SubtitleSampleStream(positionUs)
                    stream = newStream
                    streams[i] = newStream
                    streamResetFlags[i] = true
                }
            }
            return positionUs
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit

        override fun readDiscontinuity(): Long = C.TIME_UNSET

        override fun seekToUs(positionUs: Long): Long {
            stream?.seekTo(positionUs)
            return positionUs
        }

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

        override fun getBufferedPositionUs(): Long =
            if (samples.isFinished) C.TIME_END_OF_SOURCE else samples.lastTimeUs() ?: 0L

        override fun getNextLoadPositionUs(): Long =
            if (loadStarted || stream == null) C.TIME_END_OF_SOURCE else 0L

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
            if (loadStarted || stream == null) return false
            loadStarted = true
            loader.startLoading(SubtitleLoadable(samples), this, 0)
            return true
        }

        override fun isLoading(): Boolean = loader.isLoading

        override fun reevaluateBuffer(positionUs: Long) = Unit

        fun release() {
            loader.release()
        }

        override fun onLoadCompleted(loadable: SubtitleLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long) {
            samples.finish()
        }

        override fun onLoadCanceled(
            loadable: SubtitleLoadable,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            released: Boolean,
        ) = Unit

        // The server holds the request while it extracts the subtitle, which outlasts the HTTP read timeout
        // on large files, so keep retrying for about as long as its extraction timeout before giving up.
        override fun onLoadError(
            loadable: SubtitleLoadable,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            error: IOException,
            errorCount: Int,
        ): Loader.LoadErrorAction {
            if (errorCount > MAX_LOAD_ATTEMPTS) {
                Timber.w(error, "Giving up on subtitle %s", format.id)
                samples.finish()
                return Loader.DONT_RETRY
            }
            return Loader.createRetryAction(false, minOf(errorCount * RETRY_STEP_MS, MAX_RETRY_DELAY_MS))
        }

        private inner class SubtitleSampleStream(positionUs: Long) : SampleStream {
            private var formatSent = false
            private var readIndex = samples.indexForPosition(positionUs)

            fun seekTo(positionUs: Long) {
                readIndex = samples.indexForPosition(positionUs)
            }

            override fun isReady(): Boolean = true

            override fun maybeThrowError() = Unit

            override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
                if (!formatSent || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                    formatHolder.format = format
                    formatSent = true
                    return C.RESULT_FORMAT_READ
                }
                val sample = samples.get(readIndex)
                if (sample == null) {
                    if (!samples.isFinished) return C.RESULT_NOTHING_READ
                    buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    return C.RESULT_BUFFER_READ
                }
                buffer.timeUs = sample.timeUs
                buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
                if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                    buffer.ensureSpaceForWrite(sample.data.size)
                    requireNotNull(buffer.data).put(sample.data)
                }
                if (readFlags and SampleStream.FLAG_PEEK == 0) readIndex++
                return C.RESULT_BUFFER_READ
            }

            override fun skipData(positionUs: Long): Int = 0
        }
    }

    private inner class SubtitleLoadable(private val samples: SubtitleSampleBuffer) : Loader.Loadable {
        @Volatile
        private var canceled = false
        private val cueEncoder = CueEncoder()

        override fun cancelLoad() {
            canceled = true
        }

        override fun load() {
            val dataSource = dataSourceFactory.createDataSource()
            try {
                loadText(DataSourceInputStream(dataSource, DataSpec(uri)))
            } finally {
                DataSourceUtil.closeQuietly(dataSource)
            }
        }

        private fun loadText(input: InputStream) {
            val data = input.readBytes()
            if (canceled || !samples.isEmpty()) return
            val parsed = ArrayList<CuesWithTiming>()
            PARSER_FACTORY.create(sourceFormat).parse(data, 0, data.size, SubtitleParser.OutputOptions.allCues()) {
                parsed += it
            }
            for (cues in parsed.sortedBy { it.startTimeUs }) {
                val endTimeUs = if (cues.durationUs == C.TIME_UNSET) Long.MAX_VALUE else cues.endTimeUs
                samples.add(cues.startTimeUs, endTimeUs, cueEncoder.encode(cues.cues, cues.durationUs))
            }
        }
    }

    companion object {
        private val PARSER_FACTORY = DefaultSubtitleParserFactory()
        private const val MAX_LOAD_ATTEMPTS = 40
        private const val RETRY_STEP_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 5_000L

        /**
         * Whether this source can play [configuration]: the text formats media3 parses itself. SSA/ASS is
         * left to the existing path, which has its own renderer.
         */
        fun supports(configuration: MediaItem.SubtitleConfiguration): Boolean =
            when (val mimeType = configuration.mimeType) {
                null, MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_PGS -> false
                else -> PARSER_FACTORY.supportsFormat(Format.Builder().setSampleMimeType(mimeType).build())
            }
    }
}
