package ru.tinyops.android.vboard.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/** What the system recorded about how earlier runs of the app ended, to explain a crash nobody could catch. */
internal object ExitHistory {
    private const val RECENT = 3

    /** The latest abnormal exits of the app, newest first, one line each; empty when there were none. */
    fun recent(context: Context): List<String> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching {
            manager
                .getHistoricalProcessExitReasons(context.packageName, 0, RECENT * 2)
                .filter { it.reason in ABNORMAL }
                .take(RECENT)
                .map { describe(reasonName(it.reason), it.description, it.processName, it.timestamp) }
        }.getOrDefault(emptyList())
    }

    fun describe(
        reason: String,
        description: String?,
        process: String,
        time: Long,
        formatTime: (Long) -> String = LogReport::formatTime,
    ): String {
        val detail = description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "${formatTime(time)} $process: $reason$detail"
    }

    private val ABNORMAL =
        setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        )

    private fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
            else -> "dependency died"
        }
}
