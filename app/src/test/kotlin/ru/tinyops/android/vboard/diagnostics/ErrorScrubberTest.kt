package ru.tinyops.android.vboard.diagnostics

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Device
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorScrubberTest {
    private fun leakyEvent(): SentryEvent =
        SentryEvent().apply {
            message = Message().apply { formatted = "typed: my secret password" }
            user = User().apply { email = "me@example.com" }
            request = Request().apply { url = "https://my.server/v1" }
            breadcrumbs = listOf(Breadcrumb("tapped on the password field"))
            setTag("field", "password")
            setExtra("text", "private text")
            serverName = "home-laptop"
            logger = "keyboard"
            contexts.setDevice(
                Device().apply {
                    name = "Eugene's phone"
                    model = "Pixel 8"
                },
            )
            exceptions =
                listOf(
                    SentryException().apply {
                        type = "IllegalStateException"
                        module = "java.lang"
                        value = "cannot insert \"private text\""
                        stacktrace =
                            SentryStackTrace(
                                listOf(
                                    SentryStackFrame().apply {
                                        module = "ru.tinyops.android.vboard.KeyboardService"
                                        function = "commit"
                                        lineno = 42
                                        vars = mapOf("text" to "private text")
                                        contextLine = "commit(\"private text\")"
                                    },
                                ),
                            )
                    },
                )
        }

    @Test
    fun `drops the message, user, request, breadcrumbs, tags, extras and server name`() {
        val event = ErrorScrubber.scrub(leakyEvent())

        assertNull(event.message)
        assertNull(event.user)
        assertNull(event.request)
        assertTrue(event.breadcrumbs.isNullOrEmpty())
        assertTrue(event.tags.isNullOrEmpty())
        assertTrue(event.extras.isNullOrEmpty())
        assertNull(event.serverName)
        assertNull(event.logger)
    }

    @Test
    fun `drops the exception text but keeps its type and stack trace`() {
        val exception = ErrorScrubber.scrub(leakyEvent()).exceptions!!.single()

        assertNull(exception.value)
        assertEquals("IllegalStateException", exception.type)
        val frame = exception.stacktrace!!.frames!!.single()
        assertEquals("commit", frame.function)
        assertEquals(42, frame.lineno)
        assertNull(frame.vars)
        assertNull(frame.contextLine)
    }

    @Test
    fun `drops the device name but keeps the device model`() {
        val device = ErrorScrubber.scrub(leakyEvent()).contexts.device
        assertNotNull(device)
        assertNull(device!!.name)
        assertEquals("Pixel 8", device.model)
    }

    @Test
    fun `an event without exceptions passes`() {
        val event = ErrorScrubber.scrub(SentryEvent())
        assertNull(event.exceptions)
    }

    @Test
    fun `drops the source around the frames of the threads of a report`() {
        val event =
            SentryEvent().apply {
                threads =
                    listOf(
                        SentryThread().apply {
                            stacktrace =
                                SentryStackTrace(
                                    listOf(
                                        SentryStackFrame().apply {
                                            function = "draw"
                                            vars = mapOf("word" to "private")
                                            contextLine = "draw(\"private\")"
                                            preContext = listOf("val word = \"private\"")
                                            postContext = listOf("println(word)")
                                        },
                                    ),
                                )
                        },
                    )
            }

        val frame =
            ErrorScrubber
                .scrub(event)
                .threads!!
                .single()
                .stacktrace!!
                .frames!!
                .single()

        assertEquals("draw", frame.function)
        assertNull(frame.vars)
        assertNull(frame.contextLine)
        assertNull(frame.preContext)
        assertNull(frame.postContext)
    }
}
