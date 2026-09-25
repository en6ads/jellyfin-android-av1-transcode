package org.jellyfin.mobile.player.source

import android.content.Context
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.deviceprofile.CodecHelpers
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.extensions.ticks
import java.net.URLDecoder
import java.util.UUID
import kotlin.time.Duration

sealed class JellyfinMediaSource(
    val itemId: UUID,
    val item: BaseItemDto?,
    val sourceInfo: MediaSourceInfo,
    val playSessionId: String,
    playbackDetails: PlaybackDetails?,
) {
    val id: String = requireNotNull(sourceInfo.id) { "Media source has no id" }

    abstract val playMethod: PlayMethod

    var startTime: Duration = playbackDetails?.startTime ?: Duration.ZERO
    val runTime: Duration = sourceInfo.runTimeTicks?.ticks ?: Duration.ZERO

    val mediaStreams: List<MediaStream> = sourceInfo.mediaStreams.orEmpty()
    val audioStreams: List<MediaStream>
    val subtitleStreams: List<MediaStream>
    val externalSubtitleStreams: List<ExternalSubtitleStream>

    var selectedVideoStream: MediaStream? = null
        private set
    var selectedAudioStream: MediaStream? = null
        private set
    var selectedSubtitleStream: MediaStream? = null
        private set

    val selectedAudioStreamIndex: Int?
        get() = selectedAudioStream?.index
    val selectedSubtitleStreamIndex: Int
        // -1 disables subtitles, null would select the default subtitle
        // If the default should be played, it would be explicitly set above
        get() = selectedSubtitleStream?.index ?: -1

    /**
     * True only when [selectedAudioStream] was picked explicitly by the user (via the track
     * menu) - false both for the file's own untouched default AND for a track
     * [resolveDefaultAudioTrack] steered towards. QueueManager uses this to decide
     * whether a later restart (e.g. a bitrate change) must carry the selection forward as-is
     * (explicit picks always win) or is free to let it be re-evaluated from scratch for the new
     * conditions - which matters for the untouched-default case too, not just the auto-picked
     * one: a file's default track may newly qualify for auto-preference once the bitrate drops,
     * or stop qualifying once it rises back above the lossless threshold.
     */
    var isExplicitAudioTrackSelection: Boolean = false

    init {
        // Classify MediaStreams
        val audio = ArrayList<MediaStream>()
        val subtitles = ArrayList<MediaStream>()
        val externalSubtitles = ArrayList<ExternalSubtitleStream>()
        for (mediaStream in mediaStreams) {
            when (mediaStream.type) {
                MediaStreamType.VIDEO -> {
                    // Always select the first available video stream
                    if (selectedVideoStream == null) {
                        selectedVideoStream = mediaStream
                    }
                }
                MediaStreamType.AUDIO -> {
                    audio += mediaStream
                    if (mediaStream.index == (playbackDetails?.audioStreamIndex ?: sourceInfo.defaultAudioStreamIndex)) {
                        selectedAudioStream = mediaStream
                    }
                }
                MediaStreamType.SUBTITLE -> {
                    subtitles += mediaStream
                    if (mediaStream.index == (playbackDetails?.subtitleStreamIndex ?: sourceInfo.defaultSubtitleStreamIndex)) {
                        selectedSubtitleStream = mediaStream
                    }

                    // External subtitles as specified by the deliveryMethod.
                    // It is set to external either for external subtitle files or when transcoding.
                    // In the latter case, subtitles are extracted from the source file by the server.
                    if (mediaStream.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL) {
                        val deliveryUrl = mediaStream.deliveryUrl
                        val mimeType = CodecHelpers.getSubtitleMimeType(mediaStream.codec)
                        if (deliveryUrl != null && mimeType != null) {
                            externalSubtitles += ExternalSubtitleStream(
                                index = mediaStream.index,
                                deliveryUrl = deliveryUrl,
                                mimeType = mimeType,
                                displayTitle = mediaStream.displayTitle.orEmpty(),
                                language = mediaStream.language ?: Constants.LANGUAGE_UNDEFINED,
                            )
                        }
                    }
                }
                MediaStreamType.EMBEDDED_IMAGE,
                MediaStreamType.DATA,
                MediaStreamType.LYRIC,
                -> Unit // ignore
            }
        }

        audioStreams = audio
        subtitleStreams = subtitles
        externalSubtitleStreams = externalSubtitles
    }

    /**
     * Select the specified [audio stream][stream] in the source.
     *
     * @param stream The stream to select.
     * @return true if the stream was found and selected, false otherwise.
     */
    fun selectAudioStream(stream: MediaStream): Boolean {
        require(stream.type == MediaStreamType.AUDIO)
        if (mediaStreams[stream.index] !== stream) {
            return false
        }

        selectedAudioStream = stream
        return true
    }

    /**
     * The file's own intended default audio track: the first stream flagged [MediaStream.isDefault],
     * falling back to the very first audio stream if none are flagged. Confirmed on real hardware
     * that the server's `DefaultAudioStreamIndex` field comes back null on every request regardless
     * of bitrate, so it cannot be relied on - and when a source has more than one track flagged
     * default (seen in practice: both a TrueHD and an EAC3 track both marked default on the same
     * file), leaving [selectedAudioStream]/`AudioStreamIndex` unset lets the server break that tie
     * on its own, and it consistently prefers whichever track is cheaper for it to deliver (the
     * already-efficient EAC3 one) over this app's own intended "quality" default - independent of
     * bitrate cap, confirmed identical at both 15 Mbps and 40 Mbps. This must be computed and
     * pinned explicitly by the client instead; never rely on omitting the index to get "the real
     * default" back.
     */
    val fileDefaultAudioStream: MediaStream?
        get() = audioStreams.firstOrNull { it.isDefault } ?: audioStreams.firstOrNull()

    /**
     * Whether the selected video stream is Dolby Vision Profile 7 (dual-layer, base plus
     * enhancement layer).
     *
     * Confirmed repeatedly on real hardware: a Profile 7 source handed to the player untouched
     * renders a black screen, with both the hardware and software decoder toggles off making no
     * difference. Critically, it is a SILENT failure - ExoPlayer raises no error, so
     * [org.jellyfin.mobile.player.queue.QueueManager.restartPlaybackWithFallback] is never
     * invoked and nothing recovers on its own. Direct play therefore has to be prevented before
     * the server ever selects it, which is what
     * [org.jellyfin.mobile.player.queue.QueueManager.startRemotePlayback] uses this for.
     */
    val isDolbyVisionProfile7: Boolean
        get() = selectedVideoStream?.dvProfile == DOLBY_VISION_PROFILE_7

    /**
     * Resolves which audio track a non-explicit resolve (i.e. not an actual user pick) should
     * pin for the given [maxBitrate], so the app never depends on the server's own default-track
     * tie-break (see [fileDefaultAudioStream]). Returns null only if the source has no audio at
     * all.
     *
     * Only a transcode can copy one track where it would re-encode another, so this resolve's own
     * outcome decides, and direct play keeps [fileDefaultAudioStream]: it hands the file's tracks
     * to the player untouched. A transcode copies only the codecs its request lists (see
     * [transcodingAudioCodecs]), which the device profile sets per container and bitrate - ts/mkv
     * never copy eac3, for example (see TS_AUDIO_CODECS_COPY). A default track outside that list
     * gets crushed to plain AAC regardless, so if the source also has a track of the same
     * programme that *can* be copied, preferring it avoids a pointless re-encode - spatial audio
     * (e.g. EAC3/JOC) first, since a straight copy preserves it far better than a fresh AAC
     * re-encode would. Preferring a track that is not copied would only re-encode it to AAC, a
     * second lossy generation instead of a single one from the lossless default.
     *
     * A pure query, not a mutation: for [org.jellyfin.sdk.model.api.PlayMethod.TRANSCODE], the
     * server bakes the audio stream choice into `sourceInfo.transcodingUrl` at resolve time, so
     * merely reassigning [selectedAudioStream] here would be silently ignored - the caller must
     * re-resolve the media source with this stream's index to actually take effect.
     */
    fun resolveDefaultAudioTrack(maxBitrate: Int?): MediaStream? {
        val fileDefault = fileDefaultAudioStream ?: return null
        if (playMethod != PlayMethod.TRANSCODE) return fileDefault

        // Below the stereo cap no copy is possible either, so steering towards a copyable track is
        // not just pointless but actively worse. DeviceProfileBuilder caps transcoded audio to two
        // channels under this ceiling, and a downmix forces a re-encode no matter which track is
        // chosen.
        if (maxBitrate != null && maxBitrate < Constants.MULTICHANNEL_AUDIO_MIN_BITRATE) return fileDefault

        val copyableCodecs = transcodingAudioCodecs
        if (fileDefault.codec?.lowercase() in copyableCodecs) return fileDefault

        val copyable = audioStreams.filter { stream ->
            stream.codec?.lowercase() in copyableCodecs && stream.canReplace(fileDefault)
        }
        return copyable.firstOrNull { stream -> stream.audioSpatialFormat != AudioSpatialFormat.NONE }
            ?: copyable.firstOrNull()
            ?: fileDefault
    }

    /**
     * The audio codecs this transcode copies rather than re-encodes: the AudioCodec parameter of
     * its transcoding URL, the list the device profile gave for the container and bitrate the
     * server chose.
     */
    private val transcodingAudioCodecs: Set<String>
        get() {
            val query = sourceInfo.transcodingUrl?.substringAfter('?', "") ?: return emptySet()
            val parameter = query.split('&').firstOrNull { it.startsWith("AudioCodec=", ignoreCase = true) }
                ?: return emptySet()
            return URLDecoder.decode(parameter.substringAfter('='), "UTF-8").lowercase().split(',').toSet()
        }

    /**
     * Whether this track carries the same programme as [fileDefault], so it can be played in its
     * place: the same language, not a commentary, and at least as many channels as a re-encode
     * of [fileDefault] would keep.
     */
    private fun MediaStream.canReplace(fileDefault: MediaStream): Boolean {
        val minChannels = minOf(fileDefault.channels ?: 0, TRANSCODED_AUDIO_MAX_CHANNELS)
        return language == fileDefault.language && !isCommentary && (channels ?: 0) >= minChannels
    }

    /**
     * Whether this track is a commentary. Jellyfin does not pass on Matroska's commentary flag,
     * so this goes by the track's title and comment tag.
     */
    private val MediaStream.isCommentary: Boolean
        get() = listOfNotNull(title, comment).any { text ->
            COMMENTARY_WORDS.any { word -> text.contains(word, ignoreCase = true) }
        }

    /**
     * Whether a non-explicit resolve actually needs the extra round-trip to pin
     * [resolveDefaultAudioTrack] explicitly, rather than letting the server pick on its own.
     * Only true when that would change something: either more than one track is flagged default
     * (the server's own tie-break is unreliable, per [fileDefaultAudioStream]'s doc), or the
     * copy preference actually wants a different track than the file's obvious single
     * default. Skipping the extra pass otherwise avoids a pointless second PlaybackInfo call and
     * a discarded partial transcode job on every single playback start.
     */
    fun needsExplicitAudioTrackPin(maxBitrate: Int?): Boolean {
        if (audioStreams.count { it.isDefault } > 1) return true
        return resolveDefaultAudioTrack(maxBitrate) !== fileDefaultAudioStream
    }

    /**
     * Select the specified [subtitle stream][stream] in the source.
     *
     * @param stream The stream to select, or null to disable subtitles.
     * @return true if the stream was found and selected, false otherwise.
     */
    fun selectSubtitleStream(stream: MediaStream?): Boolean {
        if (stream == null) {
            selectedSubtitleStream = null
            return true
        }

        require(stream.type == MediaStreamType.SUBTITLE)
        if (mediaStreams[stream.index] !== stream) {
            return false
        }

        selectedSubtitleStream = stream
        return true
    }

    /**
     * Returns the index of the media stream within the embedded streams.
     * Useful for handling track selection in ExoPlayer, where embedded streams are mapped first.
     */
    fun getEmbeddedStreamIndex(mediaStream: MediaStream): Int {
        var index = 0
        for (stream in mediaStreams) {
            when {
                stream === mediaStream -> return index
                !stream.isExternal -> index++
            }
        }
        throw IllegalArgumentException("Invalid media stream")
    }

    /**
     * Get the formatted name of the source.
     */
    @Suppress("CyclomaticComplexMethod")
    fun getName(context: Context): String {
        return item?.let {
            buildString {
                val name = if (
                    it.type in arrayOf(BaseItemKind.PROGRAM, BaseItemKind.RECORDING) &&
                    (it.isSeries == true || !it.episodeTitle.isNullOrEmpty())
                ) {
                    it.episodeTitle
                } else {
                    it.name
                }

                val extraInfo = when (it.type) {
                    BaseItemKind.TV_CHANNEL if !it.channelNumber.isNullOrEmpty() -> it.channelNumber
                    BaseItemKind.EPISODE if it.parentIndexNumber == 0 -> context.getString(R.string.special_episode)
                    in arrayOf(BaseItemKind.EPISODE, BaseItemKind.RECORDING) if it.indexNumber != null && it.parentIndexNumber != null ->
                        "S${it.parentIndexNumber}:E${it.indexNumber}${it.indexNumberEnd?.let { n -> "-$n" } ?: ""}"
                    else -> ""
                }

                listOf(it.seriesName, extraInfo, name)
                    .filter { str -> !str.isNullOrEmpty() }
                    .joinTo(this, separator = " - ")

                if (it.type == BaseItemKind.MOVIE && it.productionYear != null) {
                    append(" (${it.productionYear})")
                } else if (it.premiereDate != null) {
                    append(" (${it.premiereDate!!.year})")
                }
            }.ifEmpty { null }
        } ?: sourceInfo.name.orEmpty()
    }

    private companion object {
        private const val DOLBY_VISION_PROFILE_7 = 7

        /** The most channels the server's audio encoders produce (its _audioTranscodeChannelLookup). */
        private const val TRANSCODED_AUDIO_MAX_CHANNELS = 6

        /** Commentary, commentaire, commento; Kommentar; comentario, comentário */
        private val COMMENTARY_WORDS = listOf("comment", "kommentar", "coment")
    }
}

data class PlaybackDetails(
    val startTime: Duration?,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
)
