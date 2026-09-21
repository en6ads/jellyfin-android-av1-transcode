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
 */
class FileLogTree(context: Context) : Timber.DebugTree() {
    private val logDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_DIRECTORY_NAME)
    private val logFile = File(logDirectory, LOG_FILE_NAME)
    private val previousLogFile = File(logDirectory, PREVIOUS_LOG_FILE_NAME)
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Serialises writes, because Timber trees are called from whichever thread logged - and for
     * playback that means the player's internal threads as well as the main one.
     */
    private val lock = Any()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, message, t)

        // Debug output is far too noisy to keep on disk, and none of it has been needed to
        // diagnose a playback failure so far.
        if (priority < Log.INFO) return

        val rendered = buildString {
            append(timestampFormat.format(Date()))
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
        }

        runCatching {
            synchronized(lock) {
                if (!logDirectory.exists() && !logDirectory.mkdirs()) return
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
        private const val LOG_FILE_NAME = "jellyfin.log"
        private const val PREVIOUS_LOG_FILE_NAME = "jellyfin-previous.log"
        private const val MAX_LOG_FILE_BYTES = 512L * 1024L

        /**
         * The log files, newest content last, skipping any that do not exist yet.
         */
        fun logFiles(context: Context): List<File> {
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_DIRECTORY_NAME)
            return listOf(
                File(directory, PREVIOUS_LOG_FILE_NAME),
                File(directory, LOG_FILE_NAME),
            ).filter(File::exists)
        }

        /**
         * Concatenates the log files into a single file suitable for sharing, and returns it, or
         * null when nothing has been logged yet.
         */
        fun collectForSharing(context: Context): File? {
            val sources = logFiles(context)
            if (sources.isEmpty()) return null

            val target = File(context.cacheDir, SHARED_LOG_FILE_NAME)
            return runCatching {
                target.writeText(sources.joinToString("\n") { file -> file.readText() })
                target
            }.getOrNull()
        }

        const val SHARED_LOG_FILE_NAME = "jellyfin-playback-log.txt"
    }
}
