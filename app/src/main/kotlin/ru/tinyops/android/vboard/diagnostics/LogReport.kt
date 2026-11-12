package ru.tinyops.android.vboard.diagnostics

import android.content.Context
import android.os.Build
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.KeyboardPrefs
import java.text.DateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

/** The error log as plain text for the clipboard, for sharing and for a saved file. */
internal object LogReport {
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun fileName(time: LocalDateTime): String = "vboard-log-${time.format(FILE_TIME)}.txt"

    /** The header with the app, the system and the settings that matter, then the entries as given. */
    fun render(
        header: String,
        entries: List<ErrorLogEntry>,
        formatTime: (Long) -> String = ::formatTime,
    ): String {
        val body =
            entries.map { entry ->
                listOfNotNull("${formatTime(entry.time)} ${entry.tag}: ${entry.message}", entry.stack)
                    .joinToString("\n")
            }
        return (listOf(header) + body).joinToString("\n\n")
    }

    /** Which app build, Android and phone produced the log, and which slide engine is on. */
    fun header(context: Context): String =
        listOf(
            "App: ${versionName(context)} (${BuildConfig.GIT_HASH}, ${BuildConfig.BUILD_TYPE})",
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.joinToString()}",
            "Slide engine: ${KeyboardPrefs(context).slideEngine.code}",
        ).joinToString("\n")

    private fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            ?: "unknown"

    fun formatTime(time: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))
}
