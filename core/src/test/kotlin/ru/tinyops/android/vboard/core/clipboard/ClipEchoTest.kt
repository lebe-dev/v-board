package ru.tinyops.android.vboard.core.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipEchoTest {
    private val echo = ClipEcho()

    @Test
    fun `a text pasted from another device is not sent back`() {
        echo.pasted("привет с компьютера", at = NOW)
        assertTrue(echo.isEcho("привет с компьютера", now = NOW + 5_000))
    }

    @Test
    fun `a pasted text is sent again once its window is over`() {
        echo.pasted("привет", at = NOW)
        assertFalse(echo.isEcho("привет", now = NOW + ClipEcho.PASTED_MILLIS + 1))
    }

    @Test
    fun `a copy whose preview matches an item from another device is an echo`() {
        echo.arrived("one  two\nthree", at = NOW)
        assertTrue(echo.isEcho("one two three", now = NOW + 1_000))
    }

    @Test
    fun `a long text matches the shortened preview`() {
        val text = "слово ".repeat(100)
        echo.arrived(ClipEcho.preview(text), at = NOW)
        assertTrue(echo.isEcho(text, now = NOW))
        assertFalse(echo.isEcho("ещё " + text, now = NOW))
    }

    @Test
    fun `an arrived item stops matching after its window`() {
        echo.arrived("текст", at = NOW)
        assertFalse(echo.isEcho("текст", now = NOW + ClipEcho.ARRIVED_MILLIS + 1))
    }

    @Test
    fun `the same copy is sent once within a few seconds`() {
        assertFalse(echo.isRepeat("копия", now = NOW))
        echo.sent("копия", at = NOW)
        assertTrue(echo.isRepeat("копия", now = NOW + 1_000))
        assertFalse(echo.isRepeat("копия", now = NOW + ClipEcho.REPEAT_MILLIS + 1))
        assertFalse(echo.isRepeat("другая", now = NOW + 1_000))
    }

    @Test
    fun `the preview is shortened like the one that arrives`() {
        assertEquals("a b", ClipEcho.preview("  a \n b "))
        val long = "x".repeat(200)
        assertEquals("x".repeat(160) + "...", ClipEcho.preview(long))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
