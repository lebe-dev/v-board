package ru.tinyops.android.vboard.core.input

import kotlin.test.Test
import kotlin.test.assertEquals

class ShiftControllerTest {
    private val shift = ShiftController(doubleTapMillis = 300)

    @Test
    fun `tap toggles one-shot shift`() {
        shift.onTap(0)
        assertEquals(ShiftState.ONCE, shift.state)
        shift.onTap(1_000)
        assertEquals(ShiftState.OFF, shift.state)
    }

    @Test
    fun `double tap locks and next tap unlocks`() {
        shift.onTap(0)
        shift.onTap(200)
        assertEquals(ShiftState.LOCKED, shift.state)
        shift.onTap(250)
        assertEquals(ShiftState.OFF, shift.state)
    }

    @Test
    fun `letter consumes one-shot shift but not caps lock`() {
        shift.onTap(0)
        shift.onLetterTyped()
        assertEquals(ShiftState.OFF, shift.state)
        shift.onLongPress()
        shift.onLetterTyped()
        assertEquals(ShiftState.LOCKED, shift.state)
    }

    @Test
    fun `auto caps never overrides caps lock`() {
        shift.applyAutoCaps(true)
        assertEquals(ShiftState.ONCE, shift.state)
        shift.applyAutoCaps(false)
        assertEquals(ShiftState.OFF, shift.state)
        shift.onLongPress()
        shift.applyAutoCaps(false)
        assertEquals(ShiftState.LOCKED, shift.state)
    }
}
