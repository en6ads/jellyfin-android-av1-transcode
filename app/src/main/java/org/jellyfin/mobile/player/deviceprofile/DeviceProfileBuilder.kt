package org.jellyfin.mobile.player.deviceprofile

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder.Companion.AVAILABLE_AUDIO_CODECS
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder.Companion.AVAILABLE_VIDEO_CODECS
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder.Companion.SUPPORTED_CONTAINER_FORMATS
import org.jellyfin.mobile.player.dolbyvision.DolbyVisionDecoder
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.ContainerProfile
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

class DeviceProfileBuilder(
    private val appPreferences: AppPreferences,
    private val context: Context,
) {
    private val supportedVideoCodecs: Array<Array<String>>
    private val supportedAudioCodecs: Array<Array<String>>
    private val videoCodecsProfiles: Map<String, Set<String>>
    private val maxAvcRawLevel: Int
    private val hardwareVideoCodecs: Set<String>

    init {
        require(
            SUPPORTED_CONTAINER_FORMATS.size == AVAILABLE_VIDEO_CODECS.size && SUPPORTED_CONTAINER_FORMATS.size == AVAILABLE_AUDIO_CODECS.size,
        )

        // Load Android-supported codecs
        val videoCodecs: MutableMap<String, DeviceCodec.Video> = HashMap()
        val audioCodecs: MutableMap<String, DeviceCodec.Audio> = HashMap()
        val hardwareVideo = HashSet<String>()
        var maxAvcLevel = 0
        val androidCodecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in androidCodecs.codecInfos) {
            if (codecInfo.isEncoder) continue

            val hardwareDecoder = codecInfo.isHardwareDecoder()

            for (mimeType in codecInfo.supportedTypes) {
                val capabilities = codecInfo.getCapabilitiesForType(mimeType)

                if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    for (pl in capabilities.profileLevels) {
                        if (pl.level > maxAvcLevel) maxAvcLevel = pl.level
                    }
                }

                val codec = DeviceCodec.from(capabilities) ?: continue
                val name = codec.name
                when (codec) {
                    is DeviceCodec.Video -> {
                        if (hardwareDecoder) hardwareVideo += name
                        if (videoCodecs.containsKey(name)) {
                            videoCodecs[name] = videoCodecs[name]!!.mergeCodec(codec)
                        } else {
                            videoCodecs[name] = codec
                        }
                    }
                    is DeviceCodec.Audio -> {
                        if (audioCodecs.containsKey(mimeType)) {
                            audioCodecs[name] = audioCodecs[name]!!.mergeCodec(codec)
                        } else {
                            audioCodecs[name] = codec
                        }
                    }
                }
            }
        }
        maxAvcRawLevel = maxAvcLevel
        hardwareVideoCodecs = hardwareVideo

        // Build map of supported codecs from device support and hardcoded data
        supportedVideoCodecs = Array(AVAILABLE_VIDEO_CODECS.size) { i ->
            AVAILABLE_VIDEO_CODECS[i].filter { codec ->
                videoCodecs.containsKey(codec)
            }.toTypedArray()
        }
        supportedAudioCodecs = Array(AVAILABLE_AUDIO_CODECS.size) { i ->
            AVAILABLE_AUDIO_CODECS[i].filter { codec ->
                audioCodecs.containsKey(codec) || codec in FORCED_AUDIO_CODECS
            }.toTypedArray()
        }
        videoCodecsProfiles = videoCodecs.entries.associate { (k, v) -> k to v.profiles }
    }

    /**
     * Build a transcode-target audio codec list gated by the client's own bitrate ceiling.
     *
     * FFmpeg can remux a lossless source track (AC-3/E-AC-3/DTS/MLP/TrueHD/FLAC) into the
     * transcoded output without re-encoding it, but those codecs carry a much higher bitrate
     * than AAC. When the client's streaming bitrate ceiling is at or above
     * [Constants.LOSSLESS_AUDIO_MIN_BITRATE] there is enough budget to let that happen;
     * otherwise [lowBitrateCopyCodecs] is advertised instead, so a low-bitrate cap isn't blown
     * by an audio track alone.
     *
     * [lowBitrateCopyCodecs] must be supplied per-container, not shared: eac3 is only safe to
     * include for mp4 ([MP4_AUDIO_CODECS_COPY] already allows it at any bitrate). Reusing that
     * for ts/mkv here would silently reintroduce eac3 as a copy target on the very path
     * [TS_AUDIO_CODECS_COPY] deliberately excludes it from, undoing the ts/EAC3 crash fix the
     * moment a low bitrate cap is in effect.
     */
    private fun transcodeAudioCodecs(maxBitrate: Int, copyCodecs: String, lowBitrateCopyCodecs: String = TRANSCODE_AUDIO_EFFICIENT): String =
        if (maxBitrate >= Constants.LOSSLESS_AUDIO_MIN_BITRATE) copyCodecs else lowBitrateCopyCodecs

    /**
     * [MP4_AUDIO_CODECS_COPY], plus TrueHD unless ASS subtitles are direct played. TrueHD in fMP4
     * only plays through the HLS factory that groups its access units, and an HLS item with ASS
     * subtitles to merge in is still built by DefaultMediaSourceFactory, see
     * HlsRoutingMediaSourceFactory.
     */
    private fun mp4AudioCodecsCopy(): String = when {
        appPreferences.exoPlayerDirectPlayAss -> MP4_AUDIO_CODECS_COPY
        else -> "$MP4_AUDIO_CODECS_COPY,truehd"
    }

    /**
     * Cap the transcode target's channel count when the bitrate ceiling is too tight to justify
     * carrying more than two channels, gated on [Constants.MULTICHANNEL_AUDIO_MIN_BITRATE].
     *
     * Note this is a LOWER, separate threshold from the [Constants.LOSSLESS_AUDIO_MIN_BITRATE]
     * used by [transcodeAudioCodecs], and the gap between them is load-bearing. Reusing the
     * lossless threshold here would suppress the mp4 EAC3/JOC copy that
     * [Constants.MP4_LOW_BITRATE_AUDIO_COPY_CODECS] deliberately allows below it - a channel cap
     * forces a downmix, and a downmix cannot be a copy. Keeping the channel cap lower leaves a
     * band where an already-efficient multichannel track still passes through intact.
     *
     * At or above the threshold this returns null (no cap), so a copy-eligible multichannel
     * source is never pointlessly downmixed.
     *
     * Declared on the TranscodingProfile rather than as an AUDIO_CHANNELS CodecProfile condition
     * on purpose: a CodecProfile condition is also evaluated for direct play, so it would push
     * 5.1 sources that currently direct-play perfectly well into a needless transcode. The
     * TranscodingProfile field only constrains streams that were already going to be transcoded.
     */
    private fun transcodeAudioChannels(maxBitrate: Int): String? =
        if (maxBitrate >= Constants.MULTICHANNEL_AUDIO_MIN_BITRATE) null else TRANSCODE_MAX_AUDIO_CHANNELS

    /**
     * Pair the stereo cap from [transcodeAudioChannels] with a matching audio bitrate ceiling,
     * so the downmix actually returns budget to the video encoder. Capping channels alone does
     * not: the server sizes the audio allocation from its own ladder and will happily spend a
     * multichannel-sized budget on two channels, which wastes the saving the cap was meant to
     * produce.
     *
     * Declared as TranscodingProfile conditions rather than on a CodecProfile because
     * TranscodingProfile.Conditions are applied by the server only after Transcode has already
     * been chosen (StreamBuilder's ApplyTranscodingConditions), so they cannot disqualify direct
     * play or direct stream. An AUDIO_BITRATE condition on a VIDEO_AUDIO CodecProfile would be
     * evaluated for those paths too and would force a transcode of any source whose audio track
     * simply happens to exceed the ceiling - precisely the opposite of the intent.
     *
     * LESS_THAN_EQUAL is honoured as a minimum against whatever the server already picked
     * (`item.AudioBitrate = Math.Min(num, item.AudioBitrate ?? num)`), so this only ever lowers
     * the allocation, never raises it.
     */
    private fun transcodeAudioConditions(maxBitrate: Int): List<ProfileCondition> =
        if (maxBitrate >= Constants.MULTICHANNEL_AUDIO_MIN_BITRATE) {
            emptyList()
        } else {
            listOf(
                ProfileCondition(
                    condition = ProfileConditionType.LESS_THAN_EQUAL,
                    property = ProfileConditionValue.AUDIO_BITRATE,
                    value = Constants.STEREO_AUDIO_MAX_BITRATE.toString(),
                    isRequired = false,
                ),
            )
        }

    private fun buildTranscodingProfiles(maxBitrate: Int): List<TranscodingProfile> {
        // fMP4 HLS can carry AV1/HEVC; MPEG-TS/MKV are the compatibility paths. Modern codecs
        // are only offered as encode targets when a hardware decoder exists for them.
        //
        // IMPORTANT, found the hard way on real hardware: the server uses two DIFFERENT lists
        // for two DIFFERENT decisions, and they can disagree. (1) Its profile-*ranking* step
        // (which of mp4/ts/mkv wins) reads the raw audioCodec string declared on each
        // TranscodingProfile below and awards a container an "exact match" the moment it sees
        // the source's codec name in that string - full stop, no further checks. (2) Its actual
        // ffmpeg-command-building step, once a container has already won, separately filters
        // that same declared list against its own hardcoded per-container HLS audio allowlist -
        // jellyfin-server's StreamBuilder.cs, only special-casing "mp4":
        //   _supportedHlsAudioCodecsTs  = ["aac", "ac3", "eac3", "mp3"]
        //   _supportedHlsAudioCodecsMp4 = ["aac", "ac3", "eac3", "mp3", "alac", "flac", "opus", "dts", "truehd"]
        // Step (1) has no idea step (2) will happen. So if a codec is declared here but isn't in
        // that container's real allowlist, ranking still credits it as a perfect match - winning
        // the container the ranking - only for step (2) to silently strip it back out, landing on
        // whatever's left instead. The fix is to never declare a codec here that the container's
        // own allowlist would filter out anyway: it can only win a ranking it can't actually
        // honor, never helps, and actively steals the win from a container that could.
        //
        // Consequence: mp4 is the only container that can carry lossless-ish audio through a
        // real transcode at all - offering av1 on mkv was tried and confirmed on real hardware to
        // not unlock anything beyond what ts already gets, since mkv falls into the ts-sized
        // allowlist too (only "mp4" is special-cased above). So mkv's copy list must match ts's
        // exactly rather than mkv's own, much broader native/direct-play codec table.
        val fmp4VideoCodecs = transcodeVideoCodecs("av1", "hevc", "h264")
        val tsVideoCodecs = transcodeVideoCodecs("hevc", "h264")

        return listOf(
            TranscodingProfile(
                type = DlnaProfileType.VIDEO,
                container = "mp4",
                videoCodec = fmp4VideoCodecs,
                audioCodec = transcodeAudioCodecs(maxBitrate, mp4AudioCodecsCopy(), LOW_BITRATE_AUDIO_COPY),
                protocol = MediaStreamProtocol.HLS,
                maxAudioChannels = transcodeAudioChannels(maxBitrate),
                conditions = transcodeAudioConditions(maxBitrate),
            ),
            TranscodingProfile(
                type = DlnaProfileType.VIDEO,
                container = "ts",
                videoCodec = tsVideoCodecs,
                audioCodec = transcodeAudioCodecs(maxBitrate, TS_AUDIO_CODECS_COPY),
                protocol = MediaStreamProtocol.HLS,
                maxAudioChannels = transcodeAudioChannels(maxBitrate),
                conditions = transcodeAudioConditions(maxBitrate),
            ),
            TranscodingProfile(
                type = DlnaProfileType.VIDEO,
                container = "mkv",
                videoCodec = tsVideoCodecs,
                audioCodec = transcodeAudioCodecs(maxBitrate, TS_AUDIO_CODECS_COPY),
                protocol = MediaStreamProtocol.HLS,
                maxAudioChannels = transcodeAudioChannels(maxBitrate),
                conditions = transcodeAudioConditions(maxBitrate),
            ),
            TranscodingProfile(
                type = DlnaProfileType.AUDIO,
                container = "mp3",
                videoCodec = "",
                audioCodec = "mp3",
                protocol = MediaStreamProtocol.HTTP,
                conditions = emptyList(),
            ),
        )
    }

    /**
     * Build a comma-separated transcode target list.
     * Codecs other than H.264 are included only when a hardware decoder is present, so an
     * encode target the device can only decode in software isn't advertised. H.264 is always
     * appended last so older devices without any of the [preferred] hardware decoders keep a
     * working fallback.
     */
    private fun transcodeVideoCodecs(vararg preferred: String): String {
        val selected = ArrayList<String>(preferred.size)
        for (codec in preferred) {
            if (codec == "h264" || codec in hardwareVideoCodecs) {
                if (codec !in selected) selected += codec
            }
        }
        if ("h264" !in selected) selected += "h264"
        return selected.joinToString(",")
    }

    fun getDeviceProfile(maxBitrate: Int = MAX_STREAMING_BITRATE): DeviceProfile {
        val containerProfiles = ArrayList<ContainerProfile>()
        val directPlayProfiles = ArrayList<DirectPlayProfile>()
        // Must come before the per-container profiles, see transcodeRangeProfiles.
        val codecProfiles = ArrayList<CodecProfile>(transcodeRangeProfiles())

        for (i in SUPPORTED_CONTAINER_FORMATS.indices) {
            val container = SUPPORTED_CONTAINER_FORMATS[i]
            if (supportedVideoCodecs[i].isNotEmpty()) {
                containerProfiles.add(
                    ContainerProfile(type = DlnaProfileType.VIDEO, container = container, conditions = emptyList()),
                )
                directPlayProfiles.add(
                    DirectPlayProfile(
                        type = DlnaProfileType.VIDEO,
                        container = container,
                        videoCodec = supportedVideoCodecs[i].joinToString(","),
                        audioCodec = supportedAudioCodecs[i].joinToString(","),
                    ),
                )
                for (videoCodec in supportedVideoCodecs[i]) {
                    codecProfiles.add(generateCodecProfile(container, videoCodec))
                }
            }
            if (supportedAudioCodecs[i].isNotEmpty()) {
                containerProfiles.add(
                    ContainerProfile(type = DlnaProfileType.AUDIO, container = container, conditions = emptyList()),
                )
                directPlayProfiles.add(
                    DirectPlayProfile(
                        type = DlnaProfileType.AUDIO,
                        container = SUPPORTED_CONTAINER_FORMATS[i],
                        audioCodec = supportedAudioCodecs[i].joinToString(","),
                    ),
                )
            }
        }

        val subtitleProfiles = when {
            appPreferences.exoPlayerDirectPlayAss -> {
                getSubtitleProfiles(EXO_EMBEDDED_SUBTITLES + SUBTITLES_SSA, EXO_EXTERNAL_SUBTITLES + SUBTITLES_SSA)
            }
            else -> getSubtitleProfiles(EXO_EMBEDDED_SUBTITLES, EXO_EXTERNAL_SUBTITLES)
        }

        return DeviceProfile(
            name = Constants.APP_INFO_NAME,
            directPlayProfiles = directPlayProfiles,
            transcodingProfiles = buildTranscodingProfiles(maxBitrate),
            containerProfiles = containerProfiles,
            codecProfiles = codecProfiles,
            subtitleProfiles = subtitleProfiles,
            maxStreamingBitrate = maxBitrate,
            maxStaticBitrate = maxBitrate.coerceAtMost(MAX_STATIC_BITRATE),
            musicStreamingTranscodingBitrate = MAX_MUSIC_TRANSCODING_BITRATE,
        )
    }

    /**
     * A codec profile for every codec this device decodes, whether or not any of its MediaCodec
     * profile constants happen to have a name in [CodecHelpers].
     *
     * Returning null for a codec with no known profile names - as this used to - loses far more
     * than the profile list. StreamBuilder matches codec profiles against the codecs it intends
     * to PRODUCE as well as the ones it might direct play, and applies their conditions to the
     * transcode target. So a missing codec profile means the server is told nothing about that
     * codec at all: no level cap, and no video range list. AV1 hit exactly this, because
     * CodecHelpers maps the AV1 mime type but has no AV1 profile mapping, so av1-rangetype was
     * never sent and an HDR-capable server had nothing to match its output range against.
     *
     * Emitting unconditionally means the next codec nobody thought to map degrades to "fewer
     * constraints" rather than "silently no constraints at all".
     */
    private fun generateCodecProfile(
        container: String,
        videoCodec: String,
    ): CodecProfile {
        val profilesSet = videoCodecsProfiles[videoCodec]

        return CodecProfile(
            type = CodecType.VIDEO,
            container = container,
            codec = videoCodec,
            applyConditions = listOf(),
            conditions = buildList {
                // Only when MediaCodec reported profiles we recognise. An empty EQUALS_ANY would
                // be satisfied by nothing, which is a stricter claim than "unknown".
                if (!profilesSet.isNullOrEmpty()) {
                    add(
                        ProfileCondition(
                            condition = ProfileConditionType.EQUALS_ANY,
                            property = ProfileConditionValue.VIDEO_PROFILE,
                            value = profilesSet.joinToString("|"),
                            isRequired = false,
                        ),
                    )
                }

                // The video ranges this device is willing to be sent. It excludes nothing, and
                // that is deliberate rather than lazy.
                //
                // This list is doing two jobs at once, which is a wart in Jellyfin's model
                // rather than in this profile. StreamBuilder applies these conditions to the
                // transcode target - which is how the server learns what output range is
                // acceptable - but it ALSO evaluates them against the source in
                // GetCompatibilityVideoCodec, and ranks transcoding profiles by the result. A
                // range this list omits therefore does not merely mean "cannot display"; it
                // demotes every transcoding profile whose codec list could have carried it.
                //
                // That is what a pair of Dolby Vision NOT_EQUALS exclusions used to do here.
                // They demoted the mp4/av1 profile for a Profile 7 source, so the ts/hevc
                // profile won the ranking and AV1 was never offered - and being tone-mapped
                // followed from that. Measured on a Profile 7 title: ts/hevc_qsv, tonemap_opencl,
                // nv12 out; with them gone, fmp4/av1_qsv, no tonemap, p010 out.
                //
                // So the DOVI entries are not a claim that this device displays Dolby Vision -
                // most cannot. They say "do not rank these sources away from my best transcode
                // target". What the display can really present in a transcode is declared by
                // transcodeRangeProfiles, which the server applies to the transcode only.
                add(
                    ProfileCondition(
                        condition = ProfileConditionType.EQUALS_ANY,
                        property = ProfileConditionValue.VIDEO_RANGE_TYPE,
                        value = ALL_VIDEO_RANGE_TYPES,
                        isRequired = false,
                    ),
                )
            },
        )
    }

    /**
     * Codec profiles that apply only to HLS transcodes, declaring the ranges this display can
     * present in a stream the server produces.
     *
     * The server matches a codec profile whose container is "hls" against the transcode's
     * sub-container, and only when building the transcode request; ranking and direct play ignore
     * it. So the per-container profiles can keep declaring every range (see generateCodecProfile)
     * while the transcode is told the truth. The server applies codec profiles in reverse order,
     * so these must come first to win. Servers without sub-container support ignore them.
     */
    private fun transcodeRangeProfiles(): List<CodecProfile> {
        val displayHdrTypes = context.displayHdrTypes()
        val dolbyVisionProfiles = DolbyVisionDecoder.supportedProfiles

        return TRANSCODE_VIDEO_CODECS.map { codec ->
            CodecProfile(
                type = CodecType.VIDEO,
                container = "hls",
                codec = codec,
                applyConditions = listOf(),
                conditions = listOf(
                    ProfileCondition(
                        condition = ProfileConditionType.EQUALS_ANY,
                        property = ProfileConditionValue.VIDEO_RANGE_TYPE,
                        value = transcodeVideoRangeTypes(codec, displayHdrTypes, dolbyVisionProfiles).joinToString("|"),
                        isRequired = false,
                    ),
                ),
            )
        }
    }

    private fun getSubtitleProfiles(embedded: Array<String>, external: Array<String>): List<SubtitleProfile> = ArrayList<SubtitleProfile>().apply {
        for (format in embedded) {
            add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED))
        }
        for (format in external) {
            add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EXTERNAL))
        }
    }

    fun getExternalPlayerProfile(): DeviceProfile = DeviceProfile(
        name = EXTERNAL_PLAYER_PROFILE_NAME,
        directPlayProfiles = listOf(
            DirectPlayProfile(type = DlnaProfileType.VIDEO, container = ""),
            DirectPlayProfile(type = DlnaProfileType.AUDIO, container = ""),
        ),
        transcodingProfiles = emptyList(),
        containerProfiles = emptyList(),
        codecProfiles = emptyList(),
        subtitleProfiles = buildList {
            EXTERNAL_PLAYER_SUBTITLES.mapTo(this) { format ->
                SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED)
            }
            EXTERNAL_PLAYER_SUBTITLES.mapTo(this) { format ->
                SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EXTERNAL)
            }
        },
        maxStreamingBitrate = Int.MAX_VALUE,
        maxStaticBitrate = Int.MAX_VALUE,
        musicStreamingTranscodingBitrate = Int.MAX_VALUE,
    )

    fun getWebCodecCapabilitiesJson(): String = buildJsonObject {
        put("h264MaxLevel", CodecHelpers.getVideoLevel("h264", maxAvcRawLevel)?.toString() ?: DEFAULT_H264_MAX_LEVEL)
    }.toString()

    companion object {
        private const val EXTERNAL_PLAYER_PROFILE_NAME = Constants.APP_INFO_NAME + " External Player"
        private const val DEFAULT_H264_MAX_LEVEL = "41"

        /**
         * Every range type the server knows, used as an allow-list that excludes nothing, for
         * direct play and transcode ranking. What a transcode may actually produce is declared
         * separately by transcodeRangeProfiles. Matching the server's own VideoRangeType enum
         * exactly matters: a value the server does not recognise is dropped rather than rejected.
         */
        private const val ALL_VIDEO_RANGE_TYPES =
            "Unknown|SDR|HDR10|HLG|DOVI|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR|DOVIWithEL|" +
                "DOVIWithHDR10Plus|DOVIWithELHDR10Plus|DOVIInvalid|HDR10Plus"

        /** The codecs the transcoding profiles can ask the server to produce. */
        private val TRANSCODE_VIDEO_CODECS = listOf("av1", "hevc", "h264")

        private const val TRANSCODE_AUDIO_EFFICIENT = "aac"

        /**
         * Channel cap applied to transcode targets under a tight bitrate ceiling, as the string
         * the TranscodingProfile.MaxAudioChannels field expects. See [transcodeAudioChannels].
         */
        private const val TRANSCODE_MAX_AUDIO_CHANNELS = "2"

        /**
         * mp4-only: below [Constants.LOSSLESS_AUDIO_MIN_BITRATE], AAC still leads so it's what
         * ffmpeg re-encodes into when no source track matches (StreamBuilder.cs:1190 always
         * targets index 0) - but a source that already has an EAC3/JOC track gets copied, not
         * re-encoded, so appending "eac3" here costs no extra bitrate over the AAC-only list
         * while letting JOC pass through even under a low bitrate cap.
         *
         * Must never be reused for ts/mkv: eac3 is exactly the codec [TS_AUDIO_CODECS_COPY]
         * deliberately excludes to avoid the ts/EAC3 MediaCodecAudioRenderer crash, and mp4 is
         * the only container confirmed safe for it.
         */
        private val LOW_BITRATE_AUDIO_COPY = Constants.MP4_LOW_BITRATE_AUDIO_COPY_CODECS.joinToString(",")

        /**
         * Must only contain codecs the server's own HLS ts audio allowlist actually permits
         * (StreamBuilder's _supportedHlsAudioCodecsTs = aac/ac3/eac3/mp3). Declaring anything
         * beyond that (mp1/mp2/dts/mlp/truehd) doesn't just fail to help - it actively harms
         * ranking: the server's profile-selection step reads this raw list and awards an "exact
         * codec match" rank to whichever codec's name it finds here, with no awareness that a
         * later, separate step will filter that same codec back out before building the ffmpeg
         * command. Confirmed on real hardware: with truehd/mlp/dts left in this list, a TrueHD
         * source got ranked as a false "exact match" on ts and won the ranking outright over mp4
         * (which honestly reports no match, since mp4's own list doesn't claim truehd) - only for
         * the ts audio allowlist to then strip truehd back out anyway, landing on a worse result
         * (MP3, AV1 lost) than mp4's honest fallback (AAC, AV1 kept) would have given.
         *
         * eac3 is deliberately absent, unlike mp4's copy list below: confirmed on real hardware
         * that a Dolby Vision Profile 7 FEL source (forced onto this ts/mkv path) crashes
         * MediaCodecAudioRenderer when its EAC3 track is selected here, while the same file's
         * TrueHD track and EAC3 delivered via mp4 both play fine - the failure is specific to
         * EAC3-over-MPEG-TS on this decoder. Losing the ranking to mp4 or falling back to AAC
         * beats crashing.
         */
        private const val TS_AUDIO_CODECS_COPY = "$TRANSCODE_AUDIO_EFFICIENT,ac3,mp3"

        /**
         * The server's own HLS mp4 audio allowlist (StreamBuilder's _supportedHlsAudioCodecsMp4)
         * permits aac/ac3/eac3/mp3/alac/flac/opus/dts/truehd - anything else in this list is a
         * no-op there regardless. Of those:
         * - dts is confirmed working on real hardware (copied losslessly into an mp4/AV1
         *   transcode without issue).
         * - eac3 (including E-AC-3 JOC/Atmos) is a standard, well-established codec for fMP4 -
         *   unlike truehd below, this isn't a rare/edge-case muxing combination - so it's
         *   included to let mp4 win the ranking over ts for JOC/Atmos sources instead of losing
         *   AV1 for no reason (ts's own copy list also includes eac3).
         * - truehd is added by [mp4AudioCodecsCopy] where it can be played. The server's ffmpeg
         *   muxes it into fMP4 correctly, but media3's fragmented MP4 extractor passes its access
         *   units on one at a time where the audio sink expects 16, so it only plays through
         *   TrueHdRechunkingHlsExtractorFactory.
         * - mlp isn't in the server's own mp4 allowlist at all, so including it would be a no-op.
         */
        private val MP4_AUDIO_CODECS_COPY = Constants.MP4_AUDIO_COPY_CODECS.joinToString(",")

        /**
         * List of container formats supported by ExoPlayer
         *
         * IMPORTANT: Don't change without updating [AVAILABLE_VIDEO_CODECS] and [AVAILABLE_AUDIO_CODECS]
         */
        private val SUPPORTED_CONTAINER_FORMATS = arrayOf(
            "mp4", "fmp4", "webm", "mkv", "mp3", "ogg", "wav", "mpegts", "flv", "aac", "flac", "3gp",
        )

        /**
         * IMPORTANT: Must have same length as [SUPPORTED_CONTAINER_FORMATS],
         * as it maps the codecs to the containers with the same index!
         */
        private val AVAILABLE_VIDEO_CODECS = arrayOf(
            // mp4
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
            // fmp4
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
            // webm
            arrayOf("vp8", "vp9", "av1"),
            // mkv
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp8", "vp9"),
            // mp3
            emptyArray(),
            // ogg
            emptyArray(),
            // wav
            emptyArray(),
            // mpegts
            arrayOf("mpeg1video", "mpeg2video", "mpeg4", "h264", "hevc"),
            // flv
            arrayOf("mpeg4", "h264"),
            // aac
            emptyArray(),
            // flac
            emptyArray(),
            // 3gp
            arrayOf("h263", "mpeg4", "h264", "hevc"),
        )

        /**
         * List of PCM codecs supported by ExoPlayer by default
         */
        private val PCM_CODECS = arrayOf(
            "pcm_s8",
            "pcm_s16be",
            "pcm_s16le",
            "pcm_s24le",
            "pcm_s32le",
            "pcm_f32le",
            "pcm_alaw",
            "pcm_mulaw",
        )

        /**
         * IMPORTANT: Must have same length as [SUPPORTED_CONTAINER_FORMATS],
         * as it maps the codecs to the containers with the same index!
         */
        private val AVAILABLE_AUDIO_CODECS = arrayOf(
            // mp4
            arrayOf("mp1", "mp2", "mp3", "aac", "alac", "ac3", "opus"),
            // fmp4
            arrayOf("mp3", "aac", "ac3", "eac3"),
            // webm
            arrayOf("vorbis", "opus"),
            // mkv
            arrayOf(*PCM_CODECS, "mp1", "mp2", "mp3", "aac", "vorbis", "opus", "flac", "alac", "ac3", "eac3", "dts", "mlp", "truehd"),
            // mp3
            arrayOf("mp3"),
            // ogg
            arrayOf("vorbis", "opus", "flac"),
            // wav
            PCM_CODECS,
            // mpegts
            arrayOf(*PCM_CODECS, "mp1", "mp2", "mp3", "aac", "ac3", "eac3", "dts", "mlp", "truehd"),
            // flv
            arrayOf("mp3", "aac"),
            // aac
            arrayOf("aac"),
            // flac
            arrayOf("flac"),
            // 3gp
            arrayOf("3gpp", "aac", "flac"),
        )

        /**
         * List of audio codecs that will be added to the device profile regardless of [MediaCodecList] advertising them.
         * This is especially useful for codecs supported by decoders integrated to ExoPlayer or added through an extension.
         */
        private val FORCED_AUDIO_CODECS = arrayOf(*PCM_CODECS, "alac", "aac", "ac3", "eac3", "dts", "mlp", "truehd")

        private val EXO_EMBEDDED_SUBTITLES = arrayOf("dvbsub", "pgssub", "srt", "subrip", "ttml")
        private val EXO_EXTERNAL_SUBTITLES = arrayOf("pgssub", "srt", "subrip", "ttml", "vtt", "webvtt")
        private val SUBTITLES_SSA = arrayOf("ssa", "ass")
        private val EXTERNAL_PLAYER_SUBTITLES = arrayOf(
            "ass", "dvbsub", "pgssub", "srt", "srt", "ssa", "subrip", "subrip", "ttml", "ttml", "vtt", "webvtt",
        )

        /**
         * Taken from Jellyfin Web:
         * https://github.com/jellyfin/jellyfin-web/blob/de690740f03c0568ba3061c4c586bd78b375d882/src/scripts/browserDeviceProfile.js#L276
         */
        private const val MAX_STREAMING_BITRATE = 120000000

        /**
         * Taken from Jellyfin Web:
         * https://github.com/jellyfin/jellyfin-web/blob/de690740f03c0568ba3061c4c586bd78b375d882/src/scripts/browserDeviceProfile.js#L372
         */
        private const val MAX_STATIC_BITRATE = 100000000

        /**
         * Taken from Jellyfin Web:
         * https://github.com/jellyfin/jellyfin-web/blob/de690740f03c0568ba3061c4c586bd78b375d882/src/scripts/browserDeviceProfile.js#L373
         */
        private const val MAX_MUSIC_TRANSCODING_BITRATE = 384000
    }
}

/**
 * isHardwareAccelerated()/isSoftwareOnly() require API 29. Below that, this mirrors the decoder-name
 * heuristic androidx.media3's MediaCodecUtil.isSoftwareOnly() already uses for the same API gap
 * (this app ships media3, so classification here matches what it already uses for playback), minus
 * its audio-only special case since this is only ever consulted for video decoders in this file.
 */
private fun MediaCodecInfo.isHardwareDecoder(): Boolean {
    if (isEncoder) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return isHardwareAccelerated && !isSoftwareOnly
    }
    val name = name.lowercase()
    val softwareOnly = name.startsWith("omx.google.") ||
        name.startsWith("omx.ffmpeg.") ||
        (name.startsWith("omx.sec.") && name.contains(".sw.")) ||
        name == "omx.qcom.video.decoder.hevcswvdec" ||
        name.startsWith("c2.android.") ||
        name.startsWith("c2.google.") ||
        (!name.startsWith("omx.") && !name.startsWith("c2."))
    return !softwareOnly
}
