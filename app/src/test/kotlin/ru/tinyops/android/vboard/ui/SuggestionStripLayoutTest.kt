package ru.tinyops.android.vboard.ui

import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.tinyops.android.vboard.core.clipboard.ClipChip

/** Checks 2-slot fallback and start/end ellipsizing on [SuggestionStripView] using native text metrics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class SuggestionStripLayoutTest {
    private val events = mutableListOf<String>()
    private lateinit var strip: SuggestionStripView

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        strip = SuggestionStripView(context, KeyboardTheme(context))
        strip.listener =
            object : SuggestionStripView.Listener {
                override fun onSuggestionPicked(word: String) {
                    events += "pick:$word"
                }

                override fun onSettingsClicked() = Unit

                override fun onClipboardClicked() = Unit

                override fun onFullscreenClicked() = Unit

                override fun onChipClicked(chip: ClipChip) = Unit

                override fun onMicUsed(modeId: String?) = Unit

                override fun onVoiceCancel() = Unit

                override fun onDictationUndo() = Unit

                override fun onProofreadUsed(held: Boolean) = Unit

                override fun onCustomButtonUsed(
                    id: String,
                    held: Boolean,
                ) = Unit
            }
        val width = context.resources.displayMetrics.widthPixels
        strip.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
    }

    private fun tap(x: Float) {
        val y = strip.height / 2f
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            strip.onTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun `long suggestions switch to two slots`() {
        val words = listOf("программирование", "программирования", "программированию")
        strip.setSuggestions(words)
        val available = strip.width - strip.height
        tap(available * 0.25f)
        tap(available * 0.75f)
        assertEquals(listOf("pick:программирование", "pick:программирования"), events)
        assertEquals(2, strip.displayedSuggestions.size)
    }

    @Test
    fun `overflowing words with the same beginning ellipsize at the start`() {
        val words =
            listOf(
                "высокопревосходительство",
                "высокопревосходительству",
                "высокопревосходительством",
            )
        strip.setSuggestions(words)
        val shown = strip.displayedSuggestions
        assertEquals(2, shown.size)
        assertTrue("Expected start ellipsis in ${shown[0]}", shown[0].startsWith("\u2026"))
        assertTrue("Expected ending preserved in ${shown[0]}", shown[0].endsWith("ство"))
        assertTrue("Expected start ellipsis in ${shown[1]}", shown[1].startsWith("\u2026"))
        assertTrue("Expected ending preserved in ${shown[1]}", shown[1].endsWith("ству"))
    }

    @Test
    fun `overflowing words with different beginnings ellipsize at the end`() {
        val words =
            listOf(
                "достопримечательностями",
                "электроэнцефалограммами",
            )
        strip.setSuggestions(words)
        val shown = strip.displayedSuggestions
        assertEquals(2, shown.size)
        assertTrue("Expected end ellipsis in ${shown[0]}", shown[0].endsWith("\u2026"))
        assertTrue("Expected beginning preserved in ${shown[0]}", shown[0].startsWith("досто"))
        assertTrue("Expected end ellipsis in ${shown[1]}", shown[1].endsWith("\u2026"))
        assertTrue("Expected beginning preserved in ${shown[1]}", shown[1].startsWith("элект"))
    }
}
