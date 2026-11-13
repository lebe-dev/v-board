package ru.tinyops.android.vboard.core.voice

import ru.tinyops.android.vboard.core.input.CursorWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickReplyTest {
    private fun window(
        text: String,
        start: Int = text.length,
        end: Int = start,
        offset: Int = 0,
    ) = CursorWindow(text, start, end, offset)

    @Test
    fun `a reply at the cursor is kept apart from the words around it`() {
        assertEquals(ReplyInsertion(3, 3, " да "), ReplyInsertion.of(window("развот", 3), InsertMode.AT_CURSOR, "да"))
        assertEquals(ReplyInsertion(4, 4, "да"), ReplyInsertion.of(window("раз ", 4), InsertMode.AT_CURSOR, "да"))
        assertEquals(ReplyInsertion(0, 0, "да"), ReplyInsertion.of(window(""), InsertMode.AT_CURSOR, "да"))
    }

    @Test
    fun `no space goes before punctuation after the cursor`() {
        assertEquals(ReplyInsertion(3, 3, " да"), ReplyInsertion.of(window("раз!", 3), InsertMode.AT_CURSOR, "да"))
    }

    @Test
    fun `a reply at the cursor goes after the selection`() {
        assertEquals(
            ReplyInsertion(3, 3, " да"),
            ReplyInsertion.of(window("раз два", 0, 3), InsertMode.AT_CURSOR, "да"),
        )
    }

    @Test
    fun `a reply replaces the selection, or goes to the cursor without one`() {
        assertEquals(
            ReplyInsertion(4, 7, "да"),
            ReplyInsertion.of(window("раз два", 4, 7), InsertMode.REPLACE_SELECTION, "да"),
        )
        assertEquals(
            ReplyInsertion(7, 7, " да"),
            ReplyInsertion.of(window("раз два"), InsertMode.REPLACE_SELECTION, "да"),
        )
    }

    @Test
    fun `a reply goes to the end of the field`() {
        assertEquals(
            ReplyInsertion(17, 17, " да"),
            ReplyInsertion.of(window("раз два", 0, offset = 10), InsertMode.APPEND, "да"),
        )
    }

    @Test
    fun `a reply replaces the whole text`() {
        assertEquals(
            ReplyInsertion(10, 17, "да"),
            ReplyInsertion.of(window("раз два", 2, 4, offset = 10), InsertMode.REPLACE_ALL, "да"),
        )
    }

    @Test
    fun `an editor that does not tell where its text is gets no insertion`() {
        assertNull(ReplyInsertion.of(window("раз", offset = -1), InsertMode.AT_CURSOR, "да"))
    }

    @Test
    fun `only the modes that need the whole text care about its length`() {
        val long = window("a".repeat(20), 10)
        assertTrue(ReplyInsertion.tooLong(long, InsertMode.REPLACE_ALL, 5))
        assertTrue(ReplyInsertion.tooLong(long, InsertMode.APPEND, 5))
        assertFalse(ReplyInsertion.tooLong(long, InsertMode.AT_CURSOR, 5))
        assertFalse(ReplyInsertion.tooLong(long, InsertMode.REPLACE_SELECTION, 5))
    }

    @Test
    fun `the id of a reply in the popup leads back to its button and reply`() {
        val id = ReplyMember.id("button", "reply")
        assertEquals("button" to "reply", ReplyMember.parse(id))
        assertNull(ReplyMember.parse("button"))
    }

    @Test
    fun `the replies of a command keep the order of the list and skip deleted ones`() {
        val replies = listOf(QuickReply("a", "Да"), QuickReply("b", "Нет"), QuickReply("c", "Позже"))
        assertEquals(listOf(replies[0], replies[2]), replies.chosen(listOf("c", "x", "a")))
    }

    @Test
    fun `a reply is stored trimmed and bounded`() {
        val reply = QuickReply("a", "  " + "я".repeat(QuickReply.MAX_LENGTH + 5) + " ").normalized()
        assertEquals(QuickReply.MAX_LENGTH, reply.text.length)
        assertFalse(QuickReply("a", "  ").valid)
    }
}
