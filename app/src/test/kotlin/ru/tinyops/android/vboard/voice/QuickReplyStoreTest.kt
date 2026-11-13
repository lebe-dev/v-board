package ru.tinyops.android.vboard.voice

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.InsertMode
import ru.tinyops.android.vboard.core.voice.QuickReply

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickReplyStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val raw = context.getSharedPreferences("quick-replies-test", Context.MODE_PRIVATE)

    private fun store() = QuickReplyStore(context, raw)

    @Test
    fun `the first start brings a few examples`() {
        val texts = store().replies.map { it.text }
        assertEquals(context.getString(R.string.quick_reply_example_thanks), texts.first())
        assertEquals(4, texts.size)
    }

    @Test
    fun `an emptied list stays empty`() {
        val store = store()
        store.replies.forEach { store.delete(it.id) }
        assertEquals(emptyList<QuickReply>(), store().replies)
    }

    @Test
    fun `a reply is stored trimmed, edited in place and moved`() {
        val store = store()
        store.replies.forEach { store.delete(it.id) }
        store.save(QuickReply("a", " Да "))
        store.save(QuickReply("b", "Нет"))
        store.save(QuickReply("a", "Конечно"))
        assertEquals(listOf(QuickReply("a", "Конечно"), QuickReply("b", "Нет")), store().replies)
        store.move("b", -1)
        assertEquals(listOf("b", "a"), store().replies.map { it.id })
        store.move("b", -1)
        assertEquals(listOf("b", "a"), store().replies.map { it.id })
    }

    @Test
    fun `the replies of a button survive storage`() {
        val buttons = CustomButtonStore(raw)
        val tap = ButtonCommand(ButtonAction.QUICK_REPLIES, insert = InsertMode.APPEND, replyIds = listOf("a", "b"))
        buttons.save(CustomButton("b", tap = tap))
        assertEquals(tap, CustomButtonStore(raw).buttons.single().tap)
    }

    @Test
    fun `quick replies act with a chosen reply that exists, and never on a hold`() {
        val prefs = VoicePrefs(context)
        val first = prefs.quickReplies.replies.first()
        val replies = ButtonCommand(ButtonAction.QUICK_REPLIES, replyIds = listOf(first.id))
        assertTrue(prefs.commandReady(replies))
        assertFalse(prefs.commandReady(replies.copy(replyIds = listOf("deleted"))))
        assertEquals(listOf(first), prefs.quickReplies.choice(replies))
        val working = requireNotNull(prefs.workingButton(CustomButton("b", tap = replies, hold = replies)))
        assertEquals(replies, working.tap)
        assertNull(working.hold)
    }
}
