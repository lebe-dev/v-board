package ru.tinyops.android.vboard.core.voice

/**
 * The text one dictation inserted, so that a single tap takes it all back. Typing or any other edit
 * during the dictation, or after it, ends the chance; the text is checked again before it is removed.
 */
class DictationUndo {
    private val inserted = StringBuilder()
    private var recording = false

    /** Whether the last dictation is over and left text that can be removed. */
    var available: Boolean = false
        private set

    /** How many characters the dictation inserted. */
    val length: Int get() = inserted.length

    fun start() {
        inserted.clear()
        recording = true
        available = false
    }

    /** Text the dictation put at the cursor. */
    fun add(text: String) {
        if (recording) inserted.append(text)
    }

    fun finish() {
        if (!recording) return
        recording = false
        available = inserted.isNotEmpty()
    }

    fun reset() {
        inserted.clear()
        recording = false
        available = false
    }

    /** How many characters before the cursor to remove, or null when [before] no longer ends with the dictated text. */
    fun lengthBefore(before: CharSequence?): Int? {
        if (!available || before == null) return null
        return inserted.length.takeIf { before.endsWith(inserted) }
    }
}
