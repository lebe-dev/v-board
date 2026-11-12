package ru.tinyops.android.vboard.diagnostics

import android.content.Context
import android.util.Log
import ru.tinyops.android.vboard.KeyboardPrefs
import java.io.File

/**
 * The error log the user can read in the settings. It is off until the user turns it on, lives only in the
 * app's private storage and is never sent anywhere; entries older than a week are dropped.
 */
internal object AppLog {
    private const val FILE = "app-error-log.jsonl"
    private const val CRASH_TAG = "Crash"

    private var store: ErrorLogStore? = null
    private var prefs: KeyboardPrefs? = null

    /** Connects the log to the app's storage and settings, and records crashes while the log is on. */
    fun init(context: Context) {
        val app = context.applicationContext
        store = ErrorLogStore(File(app.filesDir, FILE))
        prefs = KeyboardPrefs(app)
    }

    /** Hooks the log into the process crash handler; call once at launch. */
    fun recordCrashes() {
        Thread.setDefaultUncaughtExceptionHandler(crashHandler(Thread.getDefaultUncaughtExceptionHandler()))
    }

    /** Writes the crash down and hands it on, so that the system and the error reporting still see it. */
    fun crashHandler(previous: Thread.UncaughtExceptionHandler?) =
        Thread.UncaughtExceptionHandler { thread, error ->
            runCatching { record(CRASH_TAG, "The app crashed in thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }

    /** Logs an error to logcat and, while the log is on, to the error log. */
    fun error(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        Log.e(tag, message, error)
        record(tag, message, error)
    }

    fun entries(): List<ErrorLogEntry> = store?.entries().orEmpty()

    fun clear() {
        store?.clear()
    }

    private fun record(
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        if (prefs?.logErrors != true) return
        store?.append(tag, message, error?.let(Log::getStackTraceString))
    }
}
