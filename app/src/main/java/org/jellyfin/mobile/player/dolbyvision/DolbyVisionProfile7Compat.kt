package org.jellyfin.mobile.player.dolbyvision

import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import timber.log.Timber

/**
 * Presents Dolby Vision Profile 7 video tracks as HEVC so they play on devices without a decoder for it.
 *
 * Profile 7 is dual layer, and its base layer is a conformant HDR10 HEVC stream. Without a Dolby Vision
 * decoder that accepts Profile 7, no decoder binds to `video/dolby-vision` and ExoPlayer renders a black
 * frame without raising an error. An HEVC decoder plays the base layer and ignores the enhancement layer
 * and RPU NAL units. Profile 5 is left alone because its base layer is not HDR10, and media3 already
 * falls back to HEVC for Profile 8.
 */
@UnstableApi
class DolbyVisionProfile7CompatExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val rewriteEnabled: () -> Boolean,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: MutableMap<String, MutableList<String>>,
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor = object : ForwardingExtractor(extractor) {
        override fun init(output: ExtractorOutput) {
            super.init(DolbyVisionProfile7CompatExtractorOutput(output, rewriteEnabled))
        }
    }
}

@UnstableApi
private class DolbyVisionProfile7CompatExtractorOutput(
    delegate: ExtractorOutput,
    private val rewriteEnabled: () -> Boolean,
) : ForwardingExtractorOutput(delegate) {
    override fun track(id: Int, type: Int): TrackOutput {
        val track = super.track(id, type)
        if (type != C.TRACK_TYPE_VIDEO) return track

        return object : ForwardingTrackOutput(track) {
            override fun format(format: Format) {
                super.format(if (rewriteEnabled()) asPlainHevcIfProfile7(format) else format)
            }
        }
    }
}

/**
 * The Dolby Vision profiles this device's decoders advertise.
 */
object DolbyVisionDecoder {
    val supportedProfiles: Set<Int> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { info -> !info.isEncoder }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) }
                        .flatMap { type -> info.getCapabilitiesForType(type).profileLevels.asList() }
                }
                .map { profileLevel -> dolbyVisionProfileNumber(profileLevel.profile) }
                .toSet()
        }.getOrElse { error ->
            Timber.w(error, "Could not enumerate codecs; assuming no Dolby Vision decoder")
            emptySet()
        }
    }

    val supportsProfile7: Boolean
        get() = DOLBY_VISION_PROFILE_7 in supportedProfiles
}

/**
 * The profile number for a MediaCodec Dolby Vision profile constant, which has profile N at bit N.
 */
internal fun dolbyVisionProfileNumber(profileConstant: Int): Int = Integer.numberOfTrailingZeros(profileConstant)

@UnstableApi
internal fun asPlainHevcIfProfile7(format: Format): Format {
    if (!MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType, ignoreCase = true)) return format
    if (dolbyVisionProfile(format.codecs) != DOLBY_VISION_PROFILE_7) return format

    Timber.i("Playing the base layer of Dolby Vision Profile 7 track (codecs=%s)", format.codecs)

    // The dvhe/dvh1 codec string would send decoder selection back to Dolby Vision
    return format.buildUpon()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs(null)
        .build()
}

/**
 * The profile number from a Dolby Vision codec string such as `dvhe.07.06`, or null.
 */
internal fun dolbyVisionProfile(codecs: String?): Int? {
    val parts = codecs?.split('.') ?: return null
    if (parts.size < 2) return null
    if (DOLBY_VISION_CODEC_PREFIXES.none { prefix -> parts[0].equals(prefix, ignoreCase = true) }) return null
    return parts[1].toIntOrNull()
}

private const val DOLBY_VISION_PROFILE_7 = 7
private val DOLBY_VISION_CODEC_PREFIXES = listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")
