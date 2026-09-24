package org.jellyfin.mobile.utils

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Writes log output to a file so a failure can be reported without a USB cable.
 *
 * logcat is the usual way to find out why playback failed, but it needs a machine physically
 * attached to the device, or wireless debugging - whose pairing code and port are regenerated
 * every time the screen sleeps, which makes it impractical to set up while away from home. This
 * writes the same information somewhere the user can simply share from the settings screen.
 *
 * Deliberately modest: a single file with one rotation, capped by size. Logging is not worth
 * filling someone's storage over, and the interesting part of a playback failure is always the
 * last few seconds before it.
 *
 * Everything is passed through [redact] before it reaches disk, because this file exists to be
 * sent to other people and the web client logs its stored credentials, access token included.
 */
class FileLogTree(context: Context) : Timber.DebugTree() {
    private val logDirectory = logDirectory(context)
    private val logFile = File(logDirectory, LOG_FILE_NAME)
    private val previousLogFile = File(logDirectory, PREVIOUS_LOG_FILE_NAME)
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * A single thread both serialises writes and keeps file I/O off whichever thread logged -
     * for playback that includes the main thread and the player's internal ones.
     */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FileLogTree").apply { isDaemon = true }
    }

    // Debug output is far too noisy to keep on disk, and none of it has been needed to diagnose a
    // playback failure so far. Filtering here also skips formatting those messages at all.
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.INFO

    // Extends DebugTree only for its automatic class-name tags. The logcat output it would add on
    // top of JellyTree's is deliberately not produced, so super.log is never called.
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val timestamp = timestampFormat.format(Date())
        val rendered = buildString {
            append(timestamp)
            append(' ')
            append(priorityLabel(priority))
            append('/')
            append(tag ?: "Jellyfin")
            append(": ")
            append(message)
            if (t != null) {
                append('\n')
                append(stackTraceOf(t))
            }
            append('\n')
        }.let(::redact)

        writer.execute {
            runCatching {
                if (!logDirectory.exists() && !logDirectory.mkdirs()) return@runCatching
                if (logFile.length() > MAX_LOG_FILE_BYTES) {
                    previousLogFile.delete()
                    logFile.renameTo(previousLogFile)
                }
                logFile.appendText(rendered)
            }
        }
    }

    private fun priorityLabel(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }

    private fun stackTraceOf(t: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter -> t.printStackTrace(printWriter) }
        return writer.toString().trimEnd()
    }

    companion object {
        private const val LOG_DIRECTORY_NAME = "logs"
        private const val LOG_FILE_NAME = "playback.log"
        private const val PREVIOUS_LOG_FILE_NAME = "playback-previous.log"
        private const val SHARED_LOG_DIRECTORY_NAME = "shared-logs"
        private const val MAX_LOG_FILE_BYTES = 512L * 1024L
        private const val REDACTED = "<redacted>"

        /** Names used before redaction existed. Anything under them may hold a live token. */
        private val LEGACY_LOG_FILE_NAMES = listOf("jellyfin.log", "jellyfin-previous.log")

        const val SHARED_LOG_FILE_NAME = "jellyfin-playback-log.txt"

        /** JSON members such as `"AccessToken":"…"`, including inside an escaped JSON string. */
        private val SECRET_JSON_MEMBER = Regex(
            """(\\?"(?:AccessToken|access_token|ApiKey|api_key|Token|Password|Pw)\\?"\s*:\s*\\?")[^"\\]*""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Query parameters and header values: `api_key=…`, `ApiKey=…`, `X-Emby-Token: …`, and the
         * `Token="…"` field of a `MediaBrowser`/`Authorization` header.
         */
        private val SECRET_PARAMETER = Regex(
            """\b(api_key|apikey|access_token|AccessToken|X-Emby-Token|X-MediaBrowser-Token|Token)(=|:\s*)("?)[^&"\s,;]+""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Removes access tokens, API keys and passwords from [text].
         */
        internal fun redact(text: String): String = text
            .replace(SECRET_JSON_MEMBER) { match -> match.groupValues[1] + REDACTED }
            .replace(SECRET_PARAMETER) { match ->
                match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + REDACTED
            }

        /**
         * Private app storage rather than the external files directory, which other apps holding
         * the storage permission can read on Android 10 and below.
         */
        private fun logDirectory(context: Context) = File(context.filesDir, LOG_DIRECTORY_NAME)

        /**
         * Deletes log files written before redaction existed, wherever they were kept, along with
         * the last copy assembled for sharing. Safe to call on every start.
         */
        fun deleteLegacyLogs(context: Context) {
            val directories = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                .map { base -> File(base, LOG_DIRECTORY_NAME) }
            for (directory in directories) {
                for (name in LEGACY_LOG_FILE_NAMES) {
                    File(directory, name).delete()
                }
            }
            File(context.cacheDir, SHARED_LOG_FILE_NAME).delete()
        }

        /**
         * The log files, newest content last, skipping any that do not exist yet.
         */
        fun logFiles(context: Context): List<File> {
            val directory = logDirectory(context)
            return listOf(
                File(directory, PREVIOUS_LOG_FILE_NAME),
                File(directory, LOG_FILE_NAME),
            ).filter(File::exists)
        }

        /**
         * Concatenates the log files into a single file suitable for sharing, and returns it, or
         * null when nothing has been logged yet.
         *
         * Redacted again on the way out, as a second line of defence for anything written before
         * the patterns in [redact] covered it.
         */
        fun collectForSharing(context: Context): File? {
            val sources = logFiles(context)
            if (sources.isEmpty()) return null

            val directory = File(context.cacheDir, SHARED_LOG_DIRECTORY_NAME)
            val target = File(directory, SHARED_LOG_FILE_NAME)
            return runCatching {
                directory.mkdirs()
                target.writeText(redact(sources.joinToString("\n") { file -> file.readText() }))
                target
            }.getOrNull()
        }
    }
}
