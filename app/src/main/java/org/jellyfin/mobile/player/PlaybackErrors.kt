package org.jellyfin.mobile.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import org.jellyfin.mobile.R

private const val MAX_LOGGED_CAUSE_DEPTH = 5

/**
 * A message the user can act on, instead of the bare "Source error" that localizedMessage gives
 * for every IO failure.
 */
internal fun PlaybackException.describe(context: Context): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    -> context.getString(R.string.player_error_connection_too_slow)

    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    -> context.getString(R.string.player_error_connection_lost)

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    -> context.getString(R.string.player_error_server_rejected)

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> context.getString(R.string.player_error_stream_unreadable)

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    -> context.getString(R.string.player_error_unsupported_content)

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    -> context.getString(R.string.player_error_decoder_failed)

    else -> context.getString(R.string.player_error_with_code, errorCodeName)
}

/**
 * The error code name and the cause chain, with the request URI and HTTP status where there is one.
 * The query string is dropped because it carries the access token.
 */
internal fun PlaybackException.diagnosticDetail(): String = buildString {
    append("\n  errorCode: ").append(errorCodeName)

    var cause: Throwable? = this@diagnosticDetail.cause
    var depth = 0
    while (cause != null && depth < MAX_LOGGED_CAUSE_DEPTH) {
        append("\n  caused by: ").append(cause.javaClass.name)
        cause.message?.let { message -> append(" - ").append(message) }

        if (cause is HttpDataSource.InvalidResponseCodeException) {
            append("\n    responseCode: ").append(cause.responseCode)
        }
        if (cause is HttpDataSource.HttpDataSourceException) {
            append("\n    uri: ").append(cause.dataSpec.uri.buildUpon().clearQuery().build())
        }

        cause = cause.cause
        depth++
    }
}
