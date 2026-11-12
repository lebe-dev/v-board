package ru.tinyops.android.vboard.voice

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.PromptLibrary
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.RemoteRecognitionServer
import ru.tinyops.android.vboard.core.voice.SavedPrompt
import ru.tinyops.android.vboard.core.voice.SelfHostedServer
import ru.tinyops.android.vboard.core.voice.TextFeature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PromptCollectionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun freshPrefs(seed: android.content.SharedPreferences.Editor.() -> Unit = {}): VoicePrefs {
        context
            .getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply(seed)
            .commit()
        return VoicePrefs(context)
    }

    @Test
    fun `a new install uses the built-in prompts`() {
        val prefs = freshPrefs()
        assertEquals(SelfHostedServer.DEFAULT_PROMPT, prefs.prompts.text(PromptPlace.ENHANCEMENT))
        assertEquals(RemoteRecognitionServer.DEFAULT_PROMPT, prefs.remoteServer.prompt)
        assertNull(prefs.prompts.ids(PromptPlace.DICTATION))
        assertNotNull(prefs.prompts.library.find(PromptLibrary.ENHANCEMENT_ID))
        assertNotNull(prefs.prompts.library.find(PromptLibrary.RECOGNITION_ID))
    }

    @Test
    fun `the built-in prompts are added only on the first start`() {
        val prefs = freshPrefs()
        prefs.prompts.save(SavedPrompt("a", "Первый", "Первый текст."))
        val size = prefs.prompts.library.prompts.size
        assertEquals(
            size,
            VoicePrefs(context)
                .prompts.library.prompts.size,
        )
    }

    @Test
    fun `a restored collection is not replaced by the built-in prompts`() {
        val prefs = freshPrefs { putString("prompts", "[]") }
        assertEquals(0, prefs.prompts.library.prompts.size)
    }

    @Test
    fun `a saved prompt is kept and several choices are joined`() {
        val prefs = freshPrefs()
        prefs.prompts.save(SavedPrompt("a", "Первый", "Первый текст."))
        prefs.prompts.save(SavedPrompt("b", "Второй", "Второй текст."))
        prefs.prompts.setIds(PromptPlace.ENHANCEMENT, listOf("b", "a"))

        val stored = VoicePrefs(context)
        assertEquals("Первый текст.\n\nВторой текст.", stored.prompts.text(PromptPlace.ENHANCEMENT))
        assertEquals(
            "Первый",
            stored.prompts.library
                .find("a")
                ?.title,
        )
    }

    @Test
    fun `a function can follow the default prompt or choose its own`() {
        val prefs = freshPrefs()
        prefs.prompts.save(SavedPrompt("a", "Первый", "Первый текст."))
        prefs.prompts.setIds(PromptPlace.DICTATION, listOf("a"))
        assertEquals("Первый текст.", prefs.connection(TextFeature.DICTATION).server.prompt)

        prefs.prompts.setIds(PromptPlace.DICTATION, null)
        assertEquals(SelfHostedServer.DEFAULT_PROMPT, prefs.connection(TextFeature.DICTATION).server.prompt)
    }

    @Test
    fun `deleting a prompt in use resets its places`() {
        val prefs = freshPrefs()
        prefs.prompts.save(SavedPrompt("a", "Первый", "Первый текст."))
        prefs.prompts.setIds(PromptPlace.ENHANCEMENT, listOf("a"))
        prefs.prompts.setIds(PromptPlace.DICTATION, listOf("a"))
        assertEquals(listOf(PromptPlace.ENHANCEMENT, PromptPlace.DICTATION), prefs.prompts.usage("a"))

        prefs.prompts.delete("a")
        assertNull(prefs.prompts.library.find("a"))
        assertEquals(SelfHostedServer.DEFAULT_PROMPT, prefs.prompts.text(PromptPlace.ENHANCEMENT))
        assertNull(prefs.prompts.ids(PromptPlace.DICTATION))
        assertEquals(emptyList<PromptPlace>(), prefs.prompts.usage("a"))
    }

    @Test
    fun `resetting a place goes back to its built-in prompt`() {
        val prefs = freshPrefs()
        prefs.prompts.save(SavedPrompt("a", "Первый", "Первый текст."))
        prefs.prompts.setIds(PromptPlace.ENHANCEMENT, listOf("a"))
        prefs.prompts.reset(PromptPlace.ENHANCEMENT)
        assertEquals(SelfHostedServer.DEFAULT_PROMPT, prefs.prompts.text(PromptPlace.ENHANCEMENT))
    }

    @Test
    fun `the color of a prompt survives storing and is optional`() {
        val prefs = freshPrefs()
        prefs.prompts.save(SavedPrompt("c1", "Синий", "Пиши как положено.", ButtonColor.BLUE))
        prefs.prompts.save(SavedPrompt("c2", "Без цвета", "Пиши как положено."))
        val reread = VoicePrefs(context).prompts.library
        assertEquals(ButtonColor.BLUE, reread.find("c1")?.color)
        assertNull(reread.find("c2")?.color)
        assertNull(reread.find(PromptLibrary.ENHANCEMENT_ID)?.color)
    }
}
