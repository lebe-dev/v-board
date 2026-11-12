package ru.tinyops.android.vboard

import android.app.Application
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting

/** Starts error reporting first, so that a crash while the keyboard starts is reported too. */
class KeyboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.recordCrashes()
        ErrorReporting.sync(this)
    }
}
