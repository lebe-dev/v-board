package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MicButtonTest {
    private val library =
        PromptLibrary(
            listOf(
                SavedPrompt("en", "English", "Translate the text into English."),
                SavedPrompt("blank", "Blank", "   "),
            ),
        )

    private fun settings(
        recordingMode: RecordingMode = RecordingMode.AFTER_PAUSE,
        enhancementOn: Boolean = true,
        dictationReady: Boolean = true,
        serverReady: Boolean = true,
    ) = MicSettings(recordingMode, enhancementOn, dictationReady, serverReady, library)

    @Test
    fun `the default mode follows the switch of text improvement and the general way to end`() {
        val mode = MicMode("m")
        assertEquals(
            DictationPlan(RecordingMode.REPEAT_TAP, Improvement.Dictation),
            mode.plan(settings(RecordingMode.REPEAT_TAP)),
        )
        assertEquals(
            DictationPlan(RecordingMode.AFTER_PAUSE, Improvement.None),
            mode.plan(settings(enhancementOn = false)),
        )
        assertEquals(
            DictationPlan(RecordingMode.AFTER_PAUSE, Improvement.None),
            mode.plan(settings(dictationReady = false)),
        )
    }

    @Test
    fun `a mode may end the recording its own way`() {
        val afterPause = MicMode("m", ending = MicEnding.AFTER_PAUSE)
        val repeatTap = MicMode("m", ending = MicEnding.REPEAT_TAP)
        assertEquals(RecordingMode.AFTER_PAUSE, afterPause.plan(settings(RecordingMode.REPEAT_TAP))?.mode)
        assertEquals(RecordingMode.REPEAT_TAP, repeatTap.plan(settings(RecordingMode.AFTER_PAUSE))?.mode)
    }

    @Test
    fun `without processing the text is never improved`() {
        val mode = MicMode("m", MicProcessing.NONE)
        assertEquals(Improvement.None, mode.plan(settings())?.improvement)
    }

    @Test
    fun `improvement works with the switch off but needs a configured server`() {
        val mode = MicMode("m", MicProcessing.ENHANCE)
        assertEquals(Improvement.Dictation, mode.plan(settings(enhancementOn = false))?.improvement)
        assertNull(mode.plan(settings(dictationReady = false)))
    }

    @Test
    fun `prompts keep only those that still exist and have a text`() {
        val mode = MicMode("m", MicProcessing.PROMPTS, listOf("gone", "en", "blank"))
        assertEquals(Improvement.Prompts(listOf("en")), mode.plan(settings(enhancementOn = false))?.improvement)
    }

    @Test
    fun `prompts need a server and at least one prompt left`() {
        assertNull(MicMode("m", MicProcessing.PROMPTS, listOf("en")).plan(settings(serverReady = false)))
        assertNull(MicMode("m", MicProcessing.PROMPTS, listOf("gone", "blank")).plan(settings()))
        assertNull(MicMode("m", MicProcessing.PROMPTS).plan(settings()))
    }

    @Test
    fun `a tap always dictates, without improvement when its mode cannot work`() {
        val button = MicButton(tap = MicMode("t", MicProcessing.ENHANCE, ending = MicEnding.REPEAT_TAP))
        assertEquals(
            DictationPlan(RecordingMode.REPEAT_TAP, Improvement.None),
            button.tapPlan(settings(dictationReady = false)),
        )
        assertEquals(
            DictationPlan(RecordingMode.REPEAT_TAP, Improvement.Dictation),
            button.tapPlan(settings()),
        )
    }

    @Test
    fun `the hold menu leaves out the modes that cannot work`() {
        val plain = MicMode("plain", MicProcessing.NONE)
        val improved = MicMode("improved", MicProcessing.ENHANCE)
        val english = MicMode("english", MicProcessing.PROMPTS, listOf("en"))
        val button = MicButton(hold = listOf(plain, improved, english))
        assertEquals(listOf("plain", "improved", "english"), button.holdChoices(settings()).map { it.mode.id })
        val offline = settings(dictationReady = false, serverReady = false)
        assertEquals(listOf("plain"), button.holdChoices(offline).map { it.mode.id })
    }

    @Test
    fun `the default button dictates as before on a tap and offers plain and improved dictation on a hold`() {
        val button = MicButton.DEFAULT
        assertEquals(MicProcessing.DEFAULT, button.tap.processing)
        assertEquals(MicEnding.DEFAULT, button.tap.ending)
        assertEquals(listOf(MicProcessing.NONE, MicProcessing.ENHANCE), button.hold.map { it.processing })
        assertEquals(
            button.hold.size,
            button.hold
                .map { it.id }
                .distinct()
                .size,
        )
    }

    @Test
    fun `hold modes are added, replaced in place, moved and removed`() {
        val a = MicMode("a", MicProcessing.NONE)
        val b = MicMode("b", MicProcessing.ENHANCE)
        val c = MicMode("c", MicProcessing.PROMPTS, listOf("en"))
        var button = MicButton(hold = emptyList()).withHold(a).withHold(b).withHold(c)
        assertEquals(listOf("a", "b", "c"), button.hold.map { it.id })
        button = button.withHold(b.copy(ending = MicEnding.REPEAT_TAP))
        assertEquals(MicEnding.REPEAT_TAP, button.hold[1].ending)
        assertEquals(listOf("c", "a", "b"), button.movedHold("c", -2).hold.map { it.id })
        assertEquals(listOf("a", "c", "b"), button.movedHold("b", 1).hold.map { it.id })
        assertEquals(button, button.movedHold("a", -1))
        assertEquals(listOf("a", "c"), button.withoutHold("b").hold.map { it.id })
    }

    @Test
    fun `the hold menu is bounded`() {
        val full =
            (1..MicButton.MAX_HOLD).fold(MicButton(hold = emptyList())) { button, i -> button.withHold(MicMode("m$i")) }
        assertTrue(full.holdFull)
        assertEquals(full, full.withHold(MicMode("extra")))
    }

    @Test
    fun `ids are read back to their values and unknown ones fall back to the default`() {
        MicProcessing.entries.forEach { assertEquals(it, MicProcessing.fromId(it.id)) }
        MicEnding.entries.forEach { assertEquals(it, MicEnding.fromId(it.id)) }
        assertEquals(MicProcessing.DEFAULT, MicProcessing.fromId("unknown"))
        assertEquals(MicEnding.DEFAULT, MicEnding.fromId(null))
    }
}
