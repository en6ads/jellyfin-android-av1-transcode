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
 * Writes INFO and above to a size-capped log file with one rotation, so it can be shared from the
 * settings when logcat is not available. Everything is passed through [redact] first, since the web
 * client logs its stored credentials.
 */
class FileLogTree(context: Context) : Timber.DebugTree() {
    private val logDirectory = logDirectory(context)
    private val logFile = File(logDirectory, LOG_FILE_NAME)
    private val previousLogFile = File(logDirectory, PREVIOUS_LOG_FILE_NAME)
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // Serialises writes and keeps file I/O off the logging thread
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FileLogTree").apply { isDaemon = true }
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.INFO

    // Extends DebugTree for its automatic tags only; JellyTree already writes to logcat
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

        private fun logDirectory(context: Context) = File(context.filesDir, LOG_DIRECTORY_NAME)

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
         * Concatenates the log files into a single file for sharing, or returns null when nothing has
         * been logged yet.
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
