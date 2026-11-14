package ru.tinyops.android.vboard.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.MicEnding
import ru.tinyops.android.vboard.core.voice.MicMode
import ru.tinyops.android.vboard.core.voice.MicProcessing
import ru.tinyops.android.vboard.core.voice.PromptLibrary
import ru.tinyops.android.vboard.core.voice.SavedPrompt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MicModeLabelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val library =
        PromptLibrary(
            listOf(
                SavedPrompt("short", "Short", "Make the text shorter."),
                SavedPrompt("en", "English", "Translate the text into English."),
            ),
        )

    private fun label(mode: MicMode) = mode.label(context, library)

    @Test
    fun `a mode is named by its processing`() {
        assertEquals("Dictation", label(MicMode("m")))
        assertEquals("Without processing", label(MicMode("m", MicProcessing.NONE)))
        assertEquals("With improvement", label(MicMode("m", MicProcessing.ENHANCE)))
    }

    @Test
    fun `prompts are named by their titles in the order of the collection`() {
        assertEquals("Short, English", label(MicMode("m", MicProcessing.PROMPTS, listOf("en", "gone", "short"))))
        assertEquals("No prompts", label(MicMode("m", MicProcessing.PROMPTS, listOf("gone"))))
    }

    @Test
    fun `an ending of its own is added to the name`() {
        assertEquals(
            "Without processing, after a pause",
            label(MicMode("m", MicProcessing.NONE, ending = MicEnding.AFTER_PAUSE)),
        )
        assertEquals(
            "English, tap again",
            label(MicMode("m", MicProcessing.PROMPTS, listOf("en"), MicEnding.REPEAT_TAP)),
        )
    }

    @Test
    @Config(qualifiers = "ru")
    fun `names are translated`() {
        assertEquals(
            "Без обработки, после паузы",
            label(MicMode("m", MicProcessing.NONE, ending = MicEnding.AFTER_PAUSE)),
        )
        assertEquals("С улучшением", label(MicMode("m", MicProcessing.ENHANCE)))
    }

    @Test
    fun `the icon tells plain dictation from improved and prompted, whatever the ending`() {
        assertEquals(R.drawable.ic_mic, MicMode("m", MicProcessing.NONE, ending = MicEnding.REPEAT_TAP).icon)
        assertEquals(R.drawable.ic_mic, MicMode("m").icon)
        assertEquals(R.drawable.ic_mic_spark, MicMode("m", MicProcessing.ENHANCE).icon)
        assertEquals(R.drawable.ic_auto_awesome, MicMode("m", MicProcessing.PROMPTS, listOf("en")).icon)
    }
}
