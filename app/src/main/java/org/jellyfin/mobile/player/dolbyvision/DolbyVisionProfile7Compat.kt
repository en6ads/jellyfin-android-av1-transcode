package org.jellyfin.mobile.player.dolbyvision

import android.media.MediaCodecList
import android.media.MediaFormat
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
import org.jellyfin.mobile.utils.Constants
import timber.log.Timber

/**
 * Lets Dolby Vision Profile 7 play on a device with no Dolby Vision decoder, by presenting the
 * track as plain HEVC.
 *
 * Profile 7 is dual layer: an HEVC Main 10 base layer carrying an ordinary HDR10 picture, plus an
 * enhancement layer and an RPU in NAL unit types a plain decoder does not recognise. The base
 * layer alone is perfectly decodable - the obstacle is purely how the track is advertised. A
 * Profile 7 track declares itself `video/dolby-vision`, MediaCodec offers nothing that can bind
 * to it on a device without a Dolby Vision decoder, and ExoPlayer renders a black frame while
 * raising no error at all, so nothing downstream can recover.
 *
 * Rewriting the declared type to `video/hevc` is enough. The samples are not touched: an HEVC
 * decoder reads the base layer and skips the NAL types it does not know, which is exactly what
 * happened when Jellyfin 12.0 served these files remuxed with `-tag:v:0 hvc1` - same bytes, same
 * enhancement layer present, and they played. This does client side what that retag did server
 * side, so it no longer matters whether the server offers it.
 *
 * Deliberately limited to Profile 7:
 * - Profile 5 must NOT be rewritten. Its base layer is IPT rather than a conformant HDR10
 *   signal, so an HEVC decoder would produce wildly wrong colour rather than a black frame -
 *   worse than failing, because it looks like it worked.
 * - Profile 8 needs nothing. Its base layer is already HDR10 compatible and media3 falls back to
 *   HEVC for it unaided.
 *
 * What this does not do is convert the RPU to Profile 8.1, which would need libdovi. That is
 * only worth anything on hardware that has a Dolby Vision decoder but will not take dual layer;
 * such a device gets real Dolby Vision from a conversion and only HDR10 from this. On a device
 * with no Dolby Vision decoder at all the two are indistinguishable, because the RPU is ignored
 * either way.
 */
@UnstableApi
class DolbyVisionProfile7CompatExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val rewriteEnabled: () -> Boolean,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: android.net.Uri,
        responseHeaders: MutableMap<String, MutableList<String>>,
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor =
        DolbyVisionProfile7CompatExtractor(extractor, rewriteEnabled)
}

@UnstableApi
private class DolbyVisionProfile7CompatExtractor(
    delegate: Extractor,
    private val rewriteEnabled: () -> Boolean,
) : ForwardingExtractor(delegate) {
    override fun init(output: ExtractorOutput) {
        super.init(DolbyVisionProfile7CompatExtractorOutput(output, rewriteEnabled))
    }
}

@UnstableApi
private class DolbyVisionProfile7CompatExtractorOutput(
    delegate: ExtractorOutput,
    private val rewriteEnabled: () -> Boolean,
) : ForwardingExtractorOutput(delegate) {
    override fun track(id: Int, type: Int): TrackOutput {
        val track = super.track(id, type)
        return when (type) {
            C.TRACK_TYPE_VIDEO -> DolbyVisionProfile7CompatTrackOutput(track, rewriteEnabled)
            else -> track
        }
    }
}

@UnstableApi
private class DolbyVisionProfile7CompatTrackOutput(
    delegate: TrackOutput,
    private val rewriteEnabled: () -> Boolean,
) : ForwardingTrackOutput(delegate) {
    override fun format(format: Format) {
        // Read per track rather than captured once, so changing the setting takes effect on the
        // next playback instead of the next app start.
        super.format(if (rewriteEnabled()) asPlainHevcIfProfile7(format) else format)
    }
}

/**
 * The Dolby Vision profiles this device's decoders advertise.
 *
 * Queried rather than assumed, and cached, because [MediaCodecList] enumeration is not cheap and
 * the answer cannot change while the process lives. Most phones with a Dolby Vision decoder
 * advertise profiles 5 and 8 only, so the mere presence of a decoder says nothing about
 * Profile 7. A decoder that advertises Profile 7 may still mishandle dual layer, which is why the
 * base-layer override remains available to the user.
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
 * The profile number for a MediaCodec Dolby Vision profile constant. Those constants are one bit
 * per profile, with profile N at bit N: DolbyVisionProfileDvheDtb (0x80) is Profile 7,
 * DolbyVisionProfileDvheSt (0x100) is Profile 8, DolbyVisionProfileDvav110 (0x400) is Profile 10.
 */
internal fun dolbyVisionProfileNumber(profileConstant: Int): Int = Integer.numberOfTrailingZeros(profileConstant)

/**
 * Whether a Profile 7 track should be presented as HEVC, for the given setting.
 *
 * Automatic leaves the stream alone where a decoder advertises Profile 7, so that real Dolby
 * Vision plays rather than being flattened to its base layer.
 */
fun shouldRewriteProfile7(mode: String, decoderSupportsProfile7: Boolean): Boolean = when (mode) {
    Constants.DV_PROFILE_7_BASE_LAYER -> true
    Constants.DV_PROFILE_7_NEVER -> false
    else -> !decoderSupportsProfile7
}

/**
 * Returns [format] with its sample type rewritten to HEVC when it is Dolby Vision Profile 7, and
 * unchanged otherwise.
 */
@UnstableApi
internal fun asPlainHevcIfProfile7(format: Format): Format {
    if (!MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType, ignoreCase = true)) {
        return format
    }

    if (dolbyVisionProfile(format.codecs) != DOLBY_VISION_PROFILE_7) {
        return format
    }

    Timber.i("Dolby Vision Profile 7 track presented as HEVC so a plain decoder can bind (codecs=%s)", format.codecs)

    return format.buildUpon()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        // The codec string still says dvhe/dvh1, which would send decoder selection straight
        // back to Dolby Vision. Dropping it leaves selection to the sample type alone.
        .setCodecs(null)
        .build()
}

/**
 * The profile number from a Dolby Vision codec string such as `dvhe.07.06`, or null when the
 * string is absent or not in that shape.
 */
internal fun dolbyVisionProfile(codecs: String?): Int? {
    val parts = codecs?.split('.') ?: return null
    if (parts.size < 2) return null
    if (!DOLBY_VISION_CODEC_PREFIXES.any { prefix -> parts[0].equals(prefix, ignoreCase = true) }) {
        return null
    }
    return parts[1].toIntOrNull()
}

private const val DOLBY_VISION_PROFILE_7 = 7
private val DOLBY_VISION_CODEC_PREFIXES = listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")
