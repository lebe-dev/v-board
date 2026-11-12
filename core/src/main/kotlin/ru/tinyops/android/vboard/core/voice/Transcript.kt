package ru.tinyops.android.vboard.core.voice

/**
 * Recognized phrases of one dictation. With [deferred] delivery they are buffered until
 * [complete]; otherwise every phrase is handed out at once. Ported from Govorun's transcript flow.
 */
class Transcript(
    val deferred: Boolean,
) {
    private val fragments = ArrayList<String>()
    private var closed = false

    /** Phrases accepted so far. */
    var fragmentCount = 0
        private set

    /** The buffered text, for a preview while recording. */
    val pending: String get() = fragments.joinToString(SEPARATOR)

    /** Accepts one recognized phrase; returns it when it should be inserted right away. */
    fun accept(text: String): String? {
        val fragment = text.trim()
        if (closed || fragment.isEmpty()) return null
        fragmentCount++
        if (!deferred) return fragment
        fragments += fragment
        return null
    }

    /** Ends the dictation; returns the buffered text to insert, if any. */
    fun complete(): String? {
        if (closed) return null
        closed = true
        return pending.takeIf(String::isNotBlank).also { fragments.clear() }
    }

    fun discard() {
        closed = true
        fragments.clear()
        fragmentCount = 0
    }

    companion object {
        const val SEPARATOR = " "

        /** Dictated text continues a word with a space, as Govorun's text insertion does. */
        fun withLeadingSpace(
            text: String,
            textBefore: CharSequence?,
        ): String {
            val last = textBefore?.lastOrNull()
            val startsWithSpace = text.firstOrNull()?.isWhitespace() == true
            return if (last != null && !last.isWhitespace() && !startsWithSpace) " $text" else text
        }
    }
}
