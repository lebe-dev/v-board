package ru.tinyops.android.vboard.diagnostics

import io.sentry.SentryEvent
import io.sentry.protocol.SentryStackFrame

/**
 * Strips an error report down to what helps to fix the bug: the kind of error, where it happened
 * and the build and device model. Anything the user typed or said can end up in an exception text,
 * a log line, a variable or a breadcrumb, so none of those leave the phone.
 */
internal object ErrorScrubber {
    fun scrub(event: SentryEvent): SentryEvent {
        event.message = null
        event.user = null
        event.request = null
        event.breadcrumbs = null
        event.tags = null
        event.extras = null
        event.serverName = null
        event.logger = null
        event.contexts.device?.name = null
        event.exceptions?.forEach { exception ->
            exception.value = null
            exception.stacktrace?.frames?.forEach(::scrub)
        }
        event.threads?.forEach { thread -> thread.stacktrace?.frames?.forEach(::scrub) }
        return event
    }

    /** A frame keeps where it is in the code, not the values or the source lines around it. */
    private fun scrub(frame: SentryStackFrame) {
        frame.vars = null
        frame.contextLine = null
        frame.preContext = null
        frame.postContext = null
    }
}
