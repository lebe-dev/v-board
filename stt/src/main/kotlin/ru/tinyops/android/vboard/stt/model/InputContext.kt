package ru.tinyops.android.vboard.stt.model

/**
 * Editing context for a decode session: the text already committed before the
 * cursor and the active locale.
 */
data class InputContext(
    val precedingText: String = "",
    val cursorPosition: Int = precedingText.length,
    val locale: String? = null,
) {
    /** The words immediately preceding the cursor, oldest first. */
    fun contextWords(): List<String> {
        val upToCursor = precedingText.take(cursorPosition.coerceIn(0, precedingText.length))
        return upToCursor.split(WHITESPACE).filter { it.isNotEmpty() }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
