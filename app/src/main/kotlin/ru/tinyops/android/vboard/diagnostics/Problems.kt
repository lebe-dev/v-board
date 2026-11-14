package ru.tinyops.android.vboard.diagnostics

/** A problem the app survived that has no exception of its own; its stack tells where it was met. */
internal class AppProblem(
    message: String,
) : IllegalStateException(message)

/** Problems that are not exceptions, such as an editor that does not give what the keyboard needs. */
internal object Problems {
    /** Writes [message] to the error log and reports it, each only while the user has it on. */
    fun report(
        tag: String,
        message: String,
    ) {
        AppLog.error(tag, message)
        ErrorReporting.capture(AppProblem(message))
    }
}
