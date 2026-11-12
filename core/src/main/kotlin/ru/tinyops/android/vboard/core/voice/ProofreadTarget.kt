package ru.tinyops.android.vboard.core.voice

import ru.tinyops.android.vboard.core.input.CursorWindow

/** The text to proofread and where it starts in the editor, without whitespace around it. */
data class ProofreadTarget(
    val start: Int,
    val text: String,
) {
    val end: Int get() = start + text.length

    /** Whether [window] still holds this text at the same place. */
    fun matches(window: CursorWindow): Boolean {
        val position = start - window.offset
        if (window.offset < 0 || position < 0) return false
        return window.text.length >= position + text.length &&
            window.text.substring(position, position + text.length) == text
    }

    companion object {
        /** The selection, or the whole text without one; null when there is nothing to proofread. */
        fun of(window: CursorWindow): ProofreadTarget? {
            if (window.offset < 0) return null
            val selected = window.selectionEnd > window.selectionStart
            val from = if (selected) window.selectionStart else 0
            val to = if (selected) window.selectionEnd else window.text.length
            val range = window.text.subSequence(from, to)
            val text = range.trim()
            if (text.isEmpty()) return null
            val leading = range.indexOfFirst { !it.isWhitespace() }
            return ProofreadTarget(window.offset + from + leading, text.toString())
        }

        /** Without a selection the whole text is meant, and the window may not have reached its ends. */
        fun tooLong(
            window: CursorWindow,
            limit: Int,
        ): Boolean {
            if (window.selectionEnd > window.selectionStart) return false
            return window.selectionStart >= limit || window.text.length - window.selectionEnd >= limit
        }
    }
}
