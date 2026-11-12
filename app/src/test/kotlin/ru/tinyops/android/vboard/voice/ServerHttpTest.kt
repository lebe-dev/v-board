package ru.tinyops.android.vboard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ServerHttpTest {
    @Test
    fun `an answer within the limit is read as UTF-8`() {
        val body = """{"text": "Привет"}"""
        assertEquals(body, ServerHttp.readBody(ByteArrayInputStream(body.toByteArray()), limit = 1_000))
    }

    @Test
    fun `an answer of exactly the limit is still read`() {
        val body = "x".repeat(16)
        assertEquals(body, ServerHttp.readBody(ByteArrayInputStream(body.toByteArray()), limit = 16))
    }

    @Test
    fun `a larger answer fails instead of filling the memory of the keyboard`() {
        val stream = ByteArrayInputStream(ByteArray(17))
        assertThrows(IOException::class.java) { ServerHttp.readBody(stream, limit = 16) }
    }
}
