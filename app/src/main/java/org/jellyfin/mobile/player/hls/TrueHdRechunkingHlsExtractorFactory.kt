package org.jellyfin.mobile.player.hls

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Makes Dolby TrueHD copied into fMP4 HLS playable.
 *
 * media3's audio sink takes every TrueHD sample to be 16 access units, which is how its Matroska and
 * MP4 extractors hand TrueHD over. The fragmented MP4 extractor used for HLS passes each access unit
 * on by itself, so the sink counted every 1/1200 s of audio as 16 and its clock ran 16 times too
 * fast. This groups the samples the same way the other extractors do.
 */
@UnstableApi
class TrueHdRechunkingHlsExtractorFactory(
    private val delegate: HlsExtractorFactory = DefaultHlsExtractorFactory(),
) : HlsExtractorFactory {
    @Suppress("LongParameterList") // Defined by HlsExtractorFactory
    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor = RechunkingChunkExtractor(
        delegate.createExtractor(
            uri,
            format,
            muxedCaptionFormats,
            timestampAdjuster,
            responseHeaders,
            sniffingExtractorInput,
            playerId,
        ),
    )

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(
        parseSubtitlesDuringExtraction: Boolean,
    ): HlsExtractorFactory {
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int,
    ): HlsExtractorFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = delegate.getOutputTextFormat(sourceFormat)
}

@UnstableApi
private class RechunkingChunkExtractor(
    private val extractor: HlsMediaChunkExtractor,
) : HlsMediaChunkExtractor by extractor {
    override fun init(extractorOutput: ExtractorOutput) {
        extractor.init(RechunkingExtractorOutput(extractorOutput))
    }

    override fun recreate(): HlsMediaChunkExtractor = RechunkingChunkExtractor(extractor.recreate())
}

private class RechunkingExtractorOutput(private val output: ExtractorOutput) : ExtractorOutput by output {
    private val audioTracks = HashMap<Int, TrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val track = output.track(id, type)
        if (type != C.TRACK_TYPE_AUDIO) return track
        return audioTracks.getOrPut(id) { TrueHdRechunkingTrackOutput(track) }
    }
}

/**
 * Passes a TrueHD track on in samples of 16 access units, like media3's own TrueHdSampleRechunker.
 * Samples before the first sync frame are dropped, so the first sample the sink sees has a sync
 * frame to read the access unit length from. Other formats pass through untouched.
 *
 * A group can span two HLS segments: the extractor, and this with it, lives on across the segments
 * of a stream and the samples stay contiguous in the sample queue. A seek creates a new extractor.
 */
@UnstableApi
internal class TrueHdRechunkingTrackOutput(private val output: TrackOutput) : TrackOutput by output {
    private var isTrueHd = false
    private var foundSyncFrame = false
    private var sampleCount = 0
    private var timeUs = 0L
    private var flags = 0
    private var size = 0
    private var offset = 0

    override fun format(format: Format) {
        isTrueHd = format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        output.format(format)
    }

    override fun durationUs(durationUs: Long) {
        output.durationUs(durationUs)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (!isTrueHd) {
            output.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        if (!foundSyncFrame) {
            if (flags and C.BUFFER_FLAG_KEY_FRAME == 0) return
            foundSyncFrame = true
        }
        if (sampleCount == 0) {
            this.timeUs = timeUs
            this.flags = flags
            this.size = 0
        }
        this.size += size
        this.offset = offset // From the end of this sample, which ends the group so far
        sampleCount++
        if (sampleCount == Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT) {
            output.sampleMetadata(this.timeUs, this.flags, this.size, this.offset, cryptoData)
            sampleCount = 0
        }
    }
}
