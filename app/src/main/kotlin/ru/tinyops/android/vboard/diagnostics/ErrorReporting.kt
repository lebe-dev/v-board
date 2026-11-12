package ru.tinyops.android.vboard.diagnostics

import android.content.Context
import android.util.Log
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.KeyboardPrefs

/**
 * Sends crashes, ANRs and unexpected errors to the developer, only while the user agrees and only
 * in a build that has a Sentry DSN. Reports carry no content: see [ErrorScrubber].
 */
internal object ErrorReporting {
    private const val TAG = "ErrorReporting"

    /** Whether this build can report errors at all; the settings page offers the option only then. */
    val available: Boolean get() = BuildConfig.SENTRY_DSN.isNotBlank()

    /** Starts or stops reporting to match the saved choice; call it at launch and after a change. */
    fun sync(
        context: Context,
        dsn: String = BuildConfig.SENTRY_DSN,
    ) {
        val wanted = dsn.isNotBlank() && KeyboardPrefs(context).sendErrors
        if (!wanted) {
            if (Sentry.isEnabled()) Sentry.close()
            return
        }
        if (Sentry.isEnabled()) return
        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = dsn
            options.environment = ReportEnvironment.current()
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.GIT_HASH}"
            options.maxBreadcrumbs = 0
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isAttachThreads = false
            options.isEnableAutoSessionTracking = false
            options.isSendClientReports = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableActivityLifecycleBreadcrumbs = false
            options.isEnableAppLifecycleBreadcrumbs = false
            options.isEnableAppComponentBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false
            options.isEnableUserInteractionTracing = false
            options.isEnableAutoActivityLifecycleTracing = false
            options.tracesSampleRate = null
            options.setBeforeSend { event, _ -> ErrorScrubber.scrub(event) }
        }
        Log.i(TAG, "Error reporting started")
    }

    /** Reports an error the app survived but should not have met; does nothing while reporting is off. */
    fun capture(error: Throwable) {
        if (Sentry.isEnabled()) Sentry.captureException(error)
    }
}
