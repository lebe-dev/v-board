package ru.tinyops.android.vboard.voice.govorun

import android.os.DeadObjectException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GovorunCallsTest {
    @Test
    fun `a call that works gives its result`() {
        assertEquals(1, GovorunCalls.guard("test") { 1 })
    }

    @Test
    fun `a process that died is no result`() {
        assertNull(GovorunCalls.guard("test") { throw DeadObjectException() })
    }

    @Test
    fun `a refusal that the binder brings back from Govorun is no result`() {
        assertNull(GovorunCalls.guard("test") { throw SecurityException("not allowed") })
    }

    @Test
    fun `any other runtime exception from Govorun's process is no result`() {
        assertNull(GovorunCalls.guard("test") { throw IllegalArgumentException("bad session") })
        assertNull(GovorunCalls.guard("test") { throw IllegalStateException("busy") })
    }

    @Test
    fun `a cancelled dictation still ends`() {
        assertThrows(CancellationException::class.java) {
            GovorunCalls.guard("test") { throw CancellationException("cancelled") }
        }
    }
}
