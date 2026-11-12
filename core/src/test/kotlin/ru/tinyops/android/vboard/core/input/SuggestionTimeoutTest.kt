package ru.tinyops.android.vboard.core.input

import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestionTimeoutTest {
    @Test
    fun `suggestions stay for ten seconds by default`() {
        assertEquals(SuggestionTimeout.SECONDS_10, SuggestionTimeout.DEFAULT)
    }

    @Test
    fun `an unknown value falls back to the default`() {
        assertEquals(SuggestionTimeout.DEFAULT, SuggestionTimeout.fromSeconds(7))
        assertEquals(SuggestionTimeout.OFF, SuggestionTimeout.fromSeconds(0))
    }

    @Test
    fun `off means no time limit`() {
        assertEquals(0L, SuggestionTimeout.OFF.millis)
        assertEquals(5_000L, SuggestionTimeout.SECONDS_5.millis)
    }
}
