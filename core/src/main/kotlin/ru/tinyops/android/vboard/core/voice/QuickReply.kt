package ru.tinyops.android.vboard.core.voice

import ru.tinyops.android.vboard.core.input.CursorWindow

/** A phrase a quick replies button inserts as it is. */
data class QuickReply(
    val id: String,
    val text: String,
) {
    val valid: Boolean get() = text.isNotBlank()

    /** The text as it is stored: trimmed and bounded. */
    fun normalized(): QuickReply = copy(text = text.trim().take(MAX_LENGTH))

    companion object {
        const val MAX_LENGTH = 1_000
        const val MAX_COUNT = 50
    }
}

/** The replies of [ids] that still exist, in the order of the list. */
fun List<QuickReply>.chosen(ids: Collection<String>): List<QuickReply> = filter { it.id in ids }

/** The range from [start] to [end] of the field that [text] takes the place of. */
data class ReplyInsertion(
    val start: Int,
    val end: Int,
    val text: String,
) {
    companion object {
        /**
         * Where [reply] goes for [mode]. Inserted rather than replacing, it is kept apart from the words around it.
         * Null when the editor does not tell where its text is.
         */
        fun of(
            window: CursorWindow,
            mode: InsertMode,
            reply: String,
        ): ReplyInsertion? {
            if (window.offset < 0) return null
            val selected = window.selectionEnd > window.selectionStart
            return when {
                mode == InsertMode.REPLACE_ALL ->
                    ReplyInsertion(window.offset, window.offset + window.text.length, reply)
                mode == InsertMode.REPLACE_SELECTION && selected ->
                    ReplyInsertion(window.offset + window.selectionStart, window.offset + window.selectionEnd, reply)
                mode == InsertMode.APPEND -> insertion(window, window.text.length, reply)
                else -> insertion(window, window.selectionEnd, reply)
            }
        }

        /** Whether the mode needs the whole text and it may reach past [limit] characters on a side of the cursor. */
        fun tooLong(
            window: CursorWindow,
            mode: InsertMode,
            limit: Int,
        ): Boolean {
            if (mode != InsertMode.REPLACE_ALL && mode != InsertMode.APPEND) return false
            val collapsed = CursorWindow(window.text, window.selectionEnd, window.selectionEnd, window.offset)
            return ProofreadTarget.tooLong(collapsed, limit)
        }

        private fun insertion(
            window: CursorWindow,
            position: Int,
            reply: String,
        ): ReplyInsertion {
            val text = window.text
            val before = if (position > 0 && !text[position - 1].isWhitespace()) " " else ""
            val after = if (position < text.length && text[position].isLetterOrDigit()) " " else ""
            return ReplyInsertion(window.offset + position, window.offset + position, before + reply + after)
        }
    }
}

/** The rows of the popup a quick replies button opens: each stands for one reply of the button. */
object ReplyMember {
    private const val SEPARATOR = "/reply/"

    fun id(
        buttonId: String,
        replyId: String,
    ): String = buttonId + SEPARATOR + replyId

    /** The button and the reply a row stands for, or null for the id of a button. */
    fun parse(id: String): Pair<String, String>? {
        val at = id.indexOf(SEPARATOR)
        if (at < 0) return null
        return id.substring(0, at) to id.substring(at + SEPARATOR.length)
    }
}
