package ru.tinyops.android.vboard.diagnostics

import android.app.Application
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.KeyboardPrefs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProblemsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val prefs = KeyboardPrefs(context)
    private val sent = ArrayList<SentryEvent>()

    @Before
    fun setUp() {
        AppLog.init(context)
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.clear()
        Sentry.close()
    }

    @Test
    fun `a problem goes to the error log and to error reporting`() {
        prefs.logErrors = true
        startReporting()

        Problems.report("Tag", "The field did not give its text")

        val entry = AppLog.entries().single()
        assertEquals("Tag", entry.tag)
        assertEquals("The field did not give its text", entry.message)
        assertNull(entry.stack)
        assertTrue(sent.single().throwable is AppProblem)
    }

    @Test
    fun `nothing is kept or sent while both are off`() {
        Problems.report("Tag", "The field did not give its text")

        assertTrue(AppLog.entries().isEmpty())
        assertTrue(sent.isEmpty())
    }

    private fun startReporting() {
        prefs.sendErrors = true
        ErrorReporting.sync(context, dsn = "https://key@example.invalid/1")
        Sentry.configureScope { scope ->
            scope.addEventProcessor(
                object : EventProcessor {
                    override fun process(
                        event: SentryEvent,
                        hint: Hint,
                    ): SentryEvent? {
                        sent += event
                        return null
                    }
                },
            )
        }
    }
}
