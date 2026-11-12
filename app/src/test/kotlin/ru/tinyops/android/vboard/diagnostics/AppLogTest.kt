package ru.tinyops.android.vboard.diagnostics

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AppLogTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AppLog.init(context)
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.clear()
    }

    @Test
    fun `the log is off by default`() {
        assertFalse(KeyboardPrefs(context).logErrors)
    }

    @Test
    fun `nothing is written while the log is off`() {
        AppLog.error("Tag", "ignored", IllegalStateException("boom"))

        assertTrue(AppLog.entries().isEmpty())
    }

    @Test
    fun `an error is written with its stack while the log is on`() {
        KeyboardPrefs(context).logErrors = true

        AppLog.error("Tag", "failed", IllegalStateException("boom"))

        val entry = AppLog.entries().single()
        assertEquals("Tag", entry.tag)
        assertEquals("failed", entry.message)
        assertTrue(entry.stack.orEmpty().contains("IllegalStateException: boom"))
    }

    @Test
    fun `an error without an exception has no stack`() {
        KeyboardPrefs(context).logErrors = true

        AppLog.error("Tag", "plain", null)

        assertEquals(null, AppLog.entries().single().stack)
    }

    @Test
    fun `entries stay after the log is turned off`() {
        val prefs = KeyboardPrefs(context)
        prefs.logErrors = true
        AppLog.error("Tag", "kept", null)
        prefs.logErrors = false

        AppLog.error("Tag", "not written", null)

        assertEquals(listOf("kept"), AppLog.entries().map { it.message })
    }

    @Test
    fun `a crash is written and then handed on to the previous handler`() {
        KeyboardPrefs(context).logErrors = true
        var handedOn: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, error -> handedOn = error }
        val handler = AppLog.crashHandler(previous)
        val crash = IllegalStateException("fatal")

        handler.uncaughtException(Thread.currentThread(), crash)

        assertEquals("Crash", AppLog.entries().single().tag)
        assertEquals(crash, handedOn)
    }
}
