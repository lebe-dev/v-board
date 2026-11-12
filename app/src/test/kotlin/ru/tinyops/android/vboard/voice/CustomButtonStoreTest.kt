package ru.tinyops.android.vboard.voice

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonVisibility
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.InsertMode
import ru.tinyops.android.vboard.core.voice.SavedPrompt
import ru.tinyops.android.vboard.core.voice.ServerProfile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomButtonStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val raw = context.getSharedPreferences("custom-buttons-test", Context.MODE_PRIVATE)
    private val store = CustomButtonStore(raw)

    @Test
    fun `buttons survive storage with every field`() {
        val button =
            CustomButton(
                id = "b",
                tap = ButtonCommand(ButtonAction.PROMPT, "s", listOf("p1", "p2"), InsertMode.AT_CURSOR),
                hold = ButtonCommand(ButtonAction.CLEAR),
                icon = "star",
                color = ButtonColor.TEAL,
                placement = ButtonPlacement.RIGHT,
                visibility = ButtonVisibility.ALWAYS,
                name = "Перевод",
            )
        store.save(button)
        store.save(CustomButton("c"))
        assertEquals(listOf(button, CustomButton("c")), CustomButtonStore(raw).buttons)
    }

    @Test
    fun `a new button can start in the fullscreen mode`() {
        val added = store.add(ButtonVisibility.FULLSCREEN)
        assertEquals(ButtonVisibility.FULLSCREEN, store.buttons.single { it.id == added.id }.visibility)
        assertEquals(ButtonVisibility.WITH_TEXT, store.add().visibility)
    }

    @Test
    fun `a fullscreen button survives storage`() {
        val button = CustomButton("b", visibility = ButtonVisibility.FULLSCREEN)
        store.save(button)
        assertEquals(listOf(button), CustomButtonStore(raw).buttons)
    }

    @Test
    fun `buttons are rearranged and deleted`() {
        val first = store.add()
        val second = store.add()
        store.arrange(listOf(second, first))
        assertEquals(listOf(second.id, first.id), store.buttons.map { it.id })
        store.delete(second.id)
        assertEquals(listOf(first.id), store.buttons.map { it.id })
    }

    @Test
    fun `unreadable or unknown values do not break the list`() {
        raw.edit { putString("custom_buttons", "not json") }
        assertEquals(emptyList<CustomButton>(), store.buttons)
        raw.edit { putString("custom_buttons", """[{"id":"x","tap":{"action":"future"},"hold":{"action":"copy"}}]""") }
        assertEquals(listOf(CustomButton("x", hold = ButtonCommand(ButtonAction.COPY))), store.buttons)
    }

    @Test
    fun `a turned off button survives storage and is not working`() {
        val button = CustomButton("b", ButtonCommand(ButtonAction.COPY), enabled = false)
        store.save(button)
        assertEquals(listOf(button), CustomButtonStore(raw).buttons)
        assertNull(VoicePrefs(context).workingButton(button))
    }

    @Test
    fun `buttons stored before the switch existed are on`() {
        raw.edit { putString("custom_buttons", """[{"id":"x"}]""") }
        assertTrue(store.buttons.single().enabled)
    }

    @Test
    fun `a button keeps only the commands that can act`() {
        val prefs = VoicePrefs(context)
        prefs.prompts.save(SavedPrompt("p", "Короче", "Сократи этот текст, сохранив смысл"))
        val prompt = ButtonCommand(ButtonAction.PROMPT, promptIds = listOf("p"))
        val clear = ButtonCommand(ButtonAction.CLEAR)
        assertEquals(CustomButton("empty"), prefs.workingButton(CustomButton("empty")))
        assertEquals(CustomButton("b"), prefs.workingButton(CustomButton("b", tap = prompt)))
        assertEquals(CustomButton("b", hold = clear), prefs.workingButton(CustomButton("b", prompt, clear)))
        prefs.saveServer(ServerProfile(id = "s", endpoint = "https://a.example/v1", model = "m"))
        assertEquals(CustomButton("b", prompt, clear), prefs.workingButton(CustomButton("b", prompt, clear)))
        assertTrue(prefs.commandReady(prompt.copy(serverId = "deleted")))
        assertFalse(prefs.commandReady(prompt.copy(promptIds = listOf("deleted"))))
    }

    @Test
    fun `groups and their members survive storage`() {
        val group = store.addGroup(ButtonVisibility.ALWAYS)
        val member = store.add(groupId = group.id)
        val stored = CustomButtonStore(raw).buttons
        assertEquals(listOf(group, member), stored)
        assertTrue(stored[0].group)
        assertEquals(group.id, stored[1].groupId)
    }

    @Test
    fun `deleting a group deletes its members`() {
        val group = store.addGroup()
        store.add(groupId = group.id)
        val other = store.add()
        store.delete(group.id)
        assertEquals(listOf(other), store.buttons)
    }

    @Test
    fun `a command that asks for a prompt survives storage`() {
        val button =
            CustomButton("b", ButtonCommand(ButtonAction.PROMPT, "s", insert = InsertMode.APPEND, pickPrompt = true))
        store.save(button)
        assertEquals(listOf(button), CustomButtonStore(raw).buttons)
        raw.edit { putString("custom_buttons", """[{"id":"x","tap":{"action":"prompt"}}]""") }
        assertFalse(
            store.buttons
                .single()
                .tap!!
                .pickPrompt,
        )
    }

    @Test
    fun `a command that asks for a prompt needs a server and a prompt to offer`() {
        val prefs = VoicePrefs(context)
        val pick = ButtonCommand(ButtonAction.PROMPT, pickPrompt = true)
        prefs.saveServer(ServerProfile(id = "s", endpoint = "https://a.example/v1", model = "m"))
        prefs.prompts.library.prompts
            .forEach { prefs.prompts.delete(it.id) }
        assertFalse(prefs.commandReady(pick))
        prefs.prompts.save(SavedPrompt("p", "Короче", "Сократи этот текст, сохранив смысл"))
        assertTrue(prefs.commandReady(pick))
    }
}
