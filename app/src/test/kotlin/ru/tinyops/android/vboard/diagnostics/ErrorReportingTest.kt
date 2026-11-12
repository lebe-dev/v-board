package ru.tinyops.android.vboard.diagnostics

import android.app.Application
import io.sentry.Sentry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.KeyboardPrefs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ErrorReportingTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        Sentry.close()
    }

    @Test
    fun `sending errors is off by default`() {
        assertFalse(KeyboardPrefs(context).sendErrors)
    }

    @Test
    fun `nothing is started while sending errors is off`() {
        ErrorReporting.sync(context, dsn = "https://key@example.invalid/1")
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun `a build without a DSN never starts, even when the user agrees`() {
        KeyboardPrefs(context).sendErrors = true
        ErrorReporting.sync(context, dsn = "")
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun `turning the option off stops a running reporter`() {
        val prefs = KeyboardPrefs(context)
        prefs.sendErrors = true
        ErrorReporting.sync(context, dsn = "https://key@example.invalid/1")
        prefs.sendErrors = false
        ErrorReporting.sync(context, dsn = "https://key@example.invalid/1")
        assertFalse(Sentry.isEnabled())
    }
}
