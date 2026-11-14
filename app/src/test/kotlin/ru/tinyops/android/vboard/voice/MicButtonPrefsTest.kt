package ru.tinyops.android.vboard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.Improvement
import ru.tinyops.android.vboard.core.voice.MicButton
import ru.tinyops.android.vboard.core.voice.MicEnding
import ru.tinyops.android.vboard.core.voice.MicMode
import ru.tinyops.android.vboard.core.voice.MicProcessing
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.RecordingMode
import ru.tinyops.android.vboard.core.voice.SavedPrompt
import ru.tinyops.android.vboard.core.voice.ServerProfile
import ru.tinyops.android.vboard.core.voice.TextFeature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MicButtonPrefsTest {
    private val prefs = VoicePrefs(RuntimeEnvironment.getApplication())

    private fun addServer() {
        prefs.saveServer(ServerProfile("main", "Main", "https://server.example/v1", "m"))
        prefs.setFeatureServer(TextFeature.DICTATION, "main")
    }

    @Test
    fun `without a server a tap dictates plainly and the hold offers only plain dictation`() {
        assertEquals(Improvement.None, prefs.micTapPlan().improvement)
        assertFalse(prefs.micEnhanced)
        assertEquals(listOf(MicProcessing.NONE), prefs.micHoldChoices().map { it.mode.processing })
    }

    @Test
    fun `a tap improves the text only while the switch is on, a hold offers improvement anyway`() {
        addServer()
        assertEquals(Improvement.None, prefs.micTapPlan().improvement)
        assertEquals(
            listOf(MicProcessing.NONE, MicProcessing.ENHANCE),
            prefs.micHoldChoices().map { it.mode.processing },
        )
        prefs.setFeatureEnabled(TextFeature.DICTATION, true)
        assertEquals(Improvement.Dictation, prefs.micTapPlan().improvement)
        assertTrue(prefs.micEnhanced)
    }

    @Test
    fun `a tap without processing leaves the sparkle off even with improvement on`() {
        addServer()
        prefs.setFeatureEnabled(TextFeature.DICTATION, true)
        prefs.micButton.button = MicButton(tap = MicMode(MicButton.TAP_ID, MicProcessing.NONE))
        assertFalse(prefs.micEnhanced)
    }

    @Test
    fun `the ending of a mode overrides the general one`() {
        prefs.recordingMode = RecordingMode.AFTER_PAUSE
        prefs.micButton.button = MicButton(tap = MicMode(MicButton.TAP_ID, ending = MicEnding.REPEAT_TAP))
        assertEquals(RecordingMode.REPEAT_TAP, prefs.micTapPlan().mode)
    }

    @Test
    fun `prompts of a mode go to the server of dictation instead of its own prompt`() {
        addServer()
        prefs.prompts.save(SavedPrompt("en", "English", "Translate the text into English."))
        prefs.prompts.save(SavedPrompt("short", "Short", "Make the text shorter, keep the meaning."))
        prefs.prompts.setIds(PromptPlace.DICTATION, listOf("short"))
        val mode = MicMode("m", MicProcessing.PROMPTS, listOf("en"))
        prefs.micButton.button = MicButton(hold = listOf(mode))
        val plan = prefs.micHoldChoices().single().plan
        assertEquals(Improvement.Prompts(listOf("en")), plan.improvement)
        val connection = prefs.improvementConnection(plan.improvement)!!
        assertEquals("https://server.example/v1", connection.server.endpoint)
        assertEquals("Translate the text into English.", connection.server.prompt)
        assertEquals(listOf("en"), prefs.improvementPromptIds(plan.improvement))
        assertEquals(listOf("short"), prefs.improvementPromptIds(Improvement.Dictation))
    }

    @Test
    fun `plain dictation has no connection`() {
        addServer()
        assertNull(prefs.improvementConnection(Improvement.None))
        assertEquals(emptyList<String>(), prefs.improvementPromptIds(Improvement.None))
    }
}
