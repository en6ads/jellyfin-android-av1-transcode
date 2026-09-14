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
     * When [maxBitrate] is capped below [Constants.LOSSLESS_AUDIO_MIN_BITRATE], the transcoding
     * profile only advertises "aac,eac3" as a copy target (see DeviceProfileBuilder's
     * LOW_BITRATE_AUDIO_COPY) - so a lossless track like TrueHD/DTS-HD MA would be transcoded
     * down to plain AAC anyway, discarding any spatial mix. If the source also has an
     * already-efficient lossy track, switching to it up front lets the server copy it losslessly
     * instead, preferring an EAC3/JOC track (spatial audio) over a plain one when both exist.
     *
     * Returns the stream to switch to, or null if [selectedAudioStream] is already fine as-is.
     * A pure query, not a mutation: for [org.jellyfin.sdk.model.api.PlayMethod.TRANSCODE], the
     * server bakes the audio stream choice into `sourceInfo.transcodingUrl` at resolve time, so
     * merely reassigning [selectedAudioStream] here would be silently ignored - the caller must
     * re-resolve the media source with this stream's index to actually take effect.
     */
    fun findPreferredLowBitrateAudioTrack(maxBitrate: Int?): MediaStream? {
        if (maxBitrate == null || maxBitrate >= Constants.LOSSLESS_AUDIO_MIN_BITRATE) return null
        val current = selectedAudioStream ?: return null
        if (current.codec?.lowercase() in EFFICIENT_LOSSY_AUDIO_CODECS) return null

        return audioStreams.firstOrNull { stream ->
            stream.codec?.lowercase() == "eac3" && stream.audioSpatialFormat == AudioSpatialFormat.DOLBY_ATMOS
        } ?: audioStreams.firstOrNull { stream ->
            stream.codec?.lowercase() in EFFICIENT_LOSSY_AUDIO_CODECS
        }
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
        /**
         * Codecs cheap enough that a low-bitrate cap doesn't need to crush them down further -
         * matches DeviceProfileBuilder's own audio-copy allowlists (aac/ac3/eac3/mp3).
         */
        val EFFICIENT_LOSSY_AUDIO_CODECS = setOf("aac", "ac3", "eac3", "mp3")
    }
}

data class PlaybackDetails(
    val startTime: Duration?,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
)
