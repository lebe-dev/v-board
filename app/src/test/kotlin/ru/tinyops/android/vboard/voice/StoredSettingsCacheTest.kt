package ru.tinyops.android.vboard.voice

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.SavedPrompt
import ru.tinyops.android.vboard.core.voice.ServerKind
import ru.tinyops.android.vboard.core.voice.ServerProfile

/**
 * The keyboard, its controls and the settings screen each keep their own [VoicePrefs] over the same
 * preferences: a settled value is parsed once, and a value written by any of them is read anew.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StoredSettingsCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit(commit = true) { clear() }
    }

    private val server = ServerProfile(id = "s", name = "Мой", kind = ServerKind.TEXT, endpoint = "https://x")

    @Test
    fun `servers are parsed once while they stay the same`() {
        val prefs = VoicePrefs(context)
        prefs.saveServer(server)
        assertSame(prefs.servers, prefs.servers)
    }

    @Test
    fun `a server saved elsewhere is read at once`() {
        val keyboard = VoicePrefs(context)
        assertEquals(emptyList<ServerProfile>(), keyboard.servers)
        VoicePrefs(context).saveServer(server)
        assertEquals(listOf(server), keyboard.servers)
        VoicePrefs(context).deleteServer(server.id)
        assertEquals(emptyList<ServerProfile>(), keyboard.servers)
    }

    @Test
    fun `the prompt collection is parsed once and follows a change made elsewhere`() {
        val keyboard = VoicePrefs(context)
        assertSame(keyboard.prompts.library, keyboard.prompts.library)
        val prompt = SavedPrompt("p", "Перевод", "Переведи")
        VoicePrefs(context).prompts.save(prompt)
        assertEquals(prompt, keyboard.prompts.library.find("p"))
        assertSame(keyboard.prompts.library, keyboard.prompts.library)
    }

    @Test
    fun `custom buttons are parsed once and follow a change made elsewhere`() {
        val keyboard = VoicePrefs(context)
        val settings = VoicePrefs(context)
        val added = settings.customButtons.add()
        assertSame(keyboard.customButtons.buttons, keyboard.customButtons.buttons)
        settings.customButtons.save(added.copy(name = "Новое имя"))
        assertEquals(listOf(added.copy(name = "Новое имя")), keyboard.customButtons.buttons)
        settings.customButtons.delete(added.id)
        assertEquals(emptyList<CustomButton>(), keyboard.customButtons.buttons)
    }
}
