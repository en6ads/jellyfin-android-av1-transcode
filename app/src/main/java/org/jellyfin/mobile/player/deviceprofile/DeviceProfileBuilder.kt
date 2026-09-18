package org.jellyfin.mobile.player.deviceprofile

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
     * [LOSSLESS_AUDIO_MIN_BITRATE] there is enough budget to let that happen; otherwise only
     * AAC is advertised so a low-bitrate cap isn't blown by an audio track alone.
     */
    private fun transcodeAudioCodecs(maxBitrate: Int, copyCodecs: String): String =
        if (maxBitrate >= LOSSLESS_AUDIO_MIN_BITRATE) copyCodecs else TRANSCODE_AUDIO_EFFICIENT

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

        return buildList {
            add(
                TranscodingProfile(
                    type = DlnaProfileType.VIDEO,
                    container = "mp4",
                    videoCodec = fmp4VideoCodecs,
                    audioCodec = transcodeAudioCodecs(maxBitrate, MP4_AUDIO_CODECS_COPY),
                    protocol = MediaStreamProtocol.HLS,
                    conditions = emptyList(),
                ),
            )
            // Diagnostic/fallback-avoidance toggle: with nothing else declared, the server has no
            // ts/mkv profile left to fall back to, so it's stuck with mp4 (or true direct play).
            // Dolby Vision FEL video copy only works through ts, so FEL sources lose their
            // enhancement layer (full re-encode instead) while this is on.
            if (!appPreferences.exoPlayerRestrictTranscodingToMp4) {
                add(
                    TranscodingProfile(
                        type = DlnaProfileType.VIDEO,
                        container = "ts",
                        videoCodec = tsVideoCodecs,
                        audioCodec = transcodeAudioCodecs(maxBitrate, TS_AUDIO_CODECS_COPY),
                        protocol = MediaStreamProtocol.HLS,
                        conditions = emptyList(),
                    ),
                )
                add(
                    TranscodingProfile(
                        type = DlnaProfileType.VIDEO,
                        container = "mkv",
                        videoCodec = tsVideoCodecs,
                        audioCodec = transcodeAudioCodecs(maxBitrate, TS_AUDIO_CODECS_COPY),
                        protocol = MediaStreamProtocol.HLS,
                        conditions = emptyList(),
                    ),
                )
            }
            add(
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
        val codecProfiles = ArrayList<CodecProfile>()

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
                    generateCodecProfile(container, videoCodec)?.let(codecProfiles::add)
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

    private fun generateCodecProfile(
        container: String,
        videoCodec: String,
    ): CodecProfile? {
        val profilesSet = videoCodecsProfiles[videoCodec]
        if (profilesSet?.isNotEmpty() != true) {
            return null
        }

        return CodecProfile(
            type = CodecType.VIDEO,
            container = container,
            codec = videoCodec,
            applyConditions = listOf(),
            conditions = listOf(
                ProfileCondition(
                    condition = ProfileConditionType.EQUALS_ANY,
                    property = ProfileConditionValue.VIDEO_PROFILE,
                    value = profilesSet.joinToString("|"),
                    isRequired = false,
                ),
            ),
        )
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
         * Minimum client streaming bitrate ceiling, in bits per second, at which a lossless
         * audio track is allowed to be copied instead of transcoded to AAC.
         */
        private const val LOSSLESS_AUDIO_MIN_BITRATE = 25_000_000 // 25 Mbps
        private const val TRANSCODE_AUDIO_EFFICIENT = "aac"

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
         */
        private const val TS_AUDIO_CODECS_COPY = "$TRANSCODE_AUDIO_EFFICIENT,ac3,eac3,mp3"

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
         * - truehd is excluded because it produces a stream this client's mp4 extractor can't
         *   parse ("codec frame size is not set" from the muxer, and a playback error on
         *   device) even though the same audio plays fine natively inside its original mkv
         *   container and is nominally on the server's list.
         * - mlp isn't in the server's own mp4 allowlist at all, so including it would be a no-op.
         */
        private const val MP4_AUDIO_CODECS_COPY = "$TRANSCODE_AUDIO_EFFICIENT,ac3,eac3,dts"

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
        private val EXO_EXTERNAL_SUBTITLES = arrayOf("srt", "subrip", "ttml", "vtt", "webvtt")
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
