package ru.tinyops.android.vboard.core.input

/** Shift key state. */
enum class ShiftState {
    OFF,

    /** Capitalize the next letter only. */
    ONCE,

    /** Caps lock. */
    LOCKED,
    ;

    val isActive: Boolean get() = this != OFF
}

/**
 * Shift key state machine: a tap toggles one-shot shift, a double tap or a long press locks
 * it, and typing a letter releases one-shot shift.
 */
class ShiftController(
    private val doubleTapMillis: Long = DEFAULT_DOUBLE_TAP_MILLIS,
) {
    var state: ShiftState = ShiftState.OFF
        private set

    private var lastTapAt: Long? = null

    fun onTap(nowMillis: Long) {
        val previous = lastTapAt
        val isDoubleTap = previous != null && nowMillis - previous <= doubleTapMillis
        if (isDoubleTap && state != ShiftState.LOCKED) {
            state = ShiftState.LOCKED
            lastTapAt = null
            return
        }
        state = if (state == ShiftState.OFF) ShiftState.ONCE else ShiftState.OFF
        lastTapAt = nowMillis
    }

    fun onLongPress() {
        state = ShiftState.LOCKED
        lastTapAt = null
    }

    /** Called after a letter was typed: one-shot shift is consumed. */
    fun onLetterTyped() {
        if (state == ShiftState.ONCE) state = ShiftState.OFF
    }

    /** Applies the editor's auto-capitalization; never overrides caps lock. */
    fun applyAutoCaps(capitalize: Boolean) {
        if (state == ShiftState.LOCKED) return
        state = if (capitalize) ShiftState.ONCE else ShiftState.OFF
    }

    fun reset() {
        state = ShiftState.OFF
        lastTapAt = null
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_MILLIS: Long = 350
    }
}
