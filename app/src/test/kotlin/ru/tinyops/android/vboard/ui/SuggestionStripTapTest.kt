package ru.tinyops.android.vboard.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.clipboard.ClipChip
import ru.tinyops.android.vboard.core.clipboard.ClipKind
import ru.tinyops.android.vboard.voice.ProofreadState
import ru.tinyops.android.vboard.voice.VoiceState

/** Taps that the strip must not lose: a finger that rolls at lift-off, and updates that come while it is down. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class SuggestionStripTapTest {
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

                override fun onMicClicked() {
                    events += "mic"
                }

                override fun onVoiceCancel() = Unit

                override fun onProofreadClicked() = Unit

                override fun onProofreadHeld() = Unit

                override fun onCustomButtonUsed(
                    id: String,
                    held: Boolean,
                ) {
                    events += if (held) "hold:$id" else "custom:$id"
                }
            }
        // The popup of a group needs a window to open in.
        Robolectric
            .buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
            .setContentView(strip)
        ShadowLooper.idleMainLooper()
        remeasure(context.resources.displayMetrics.widthPixels)
    }

    private fun remeasure(width: Int = strip.width) {
        strip.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
    }

    private val row get() = KeyboardTheme(strip.context).suggestionHeight.toInt()
    private val mic get() = strip.width - strip.height / 2f
    private val middle get() = strip.width / 2f

    private fun tap(
        x: Float,
        y: Float = row / 2f,
    ) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, y)
    }

    private fun buttons(vararg ids: String) = ids.map { StripButton(it, R.drawable.ic_broom, ProofreadState.IDLE) }

    private fun many(count: Int) = buttons(*Array(count) { "b$it" })

    private fun touch(
        action: Int,
        x: Float,
        y: Float = row / 2f,
    ) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        strip.onTouchEvent(event)
        event.recycle()
    }

    private val slop get() = ViewConfiguration.get(strip.context).scaledTouchSlop

    @Test
    fun `a finger that slips onto the next button at lift-off still taps the first one`() {
        strip.buttonWidthScale = 0.6f
        strip.customButtons = buttons("a", "b")
        val narrow = row * 0.6f
        val edge = row + narrow
        touch(MotionEvent.ACTION_DOWN, edge - 2f)
        touch(MotionEvent.ACTION_MOVE, edge + slop / 2f)
        touch(MotionEvent.ACTION_UP, edge + slop / 2f)
        assertEquals(listOf("custom:a"), events)
    }

    @Test
    fun `a finger that slips below the strip at lift-off still taps`() {
        strip.secondRow = true
        strip.customButtons = many(20)
        remeasure()
        touch(MotionEvent.ACTION_DOWN, 1.5f * row, row - 2f)
        touch(MotionEvent.ACTION_UP, 1.5f * row, row + slop / 2f)
        assertEquals(listOf("custom:b0"), events)
    }

    @Test
    fun `the buttons published again under the finger keep the tap`() {
        strip.customButtons = buttons("a", "b")
        touch(MotionEvent.ACTION_DOWN, 1.5f * row)
        strip.plannedButtons = 2
        strip.customButtons = buttons("a", "b").map { it.copy(state = ProofreadState.UNDO) }
        touch(MotionEvent.ACTION_UP, 1.5f * row)
        assertEquals(listOf("custom:a"), events)
    }

    @Test
    fun `a button that went away under the finger is not tapped`() {
        strip.customButtons = buttons("a", "b")
        touch(MotionEvent.ACTION_DOWN, 1.5f * row)
        strip.customButtons = buttons("b")
        touch(MotionEvent.ACTION_UP, 1.5f * row)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `the dictation status changing under the finger keeps the tap on the microphone`() {
        strip.voiceState = VoiceState(VoiceState.Phase.LISTENING)
        touch(MotionEvent.ACTION_DOWN, mic)
        strip.voiceState = VoiceState(VoiceState.Phase.LISTENING, speech = true, preview = "Привет")
        touch(MotionEvent.ACTION_UP, mic)
        assertEquals(listOf("mic"), events)
    }

    @Test
    fun `chips going away do not take the tap from a button`() {
        strip.customButtons = buttons("a")
        strip.setClipChips(listOf(ClipChip(ClipKind.TEXT, "Привет", "Привет")))
        touch(MotionEvent.ACTION_DOWN, 1.5f * row)
        strip.setClipChips(emptyList())
        touch(MotionEvent.ACTION_UP, 1.5f * row)
        assertEquals(listOf("custom:a"), events)
    }

    @Test
    fun `new suggestions under the finger drop the tap on a slot`() {
        strip.setSuggestions(listOf("один"))
        touch(MotionEvent.ACTION_DOWN, middle)
        strip.setSuggestions(listOf("два"))
        touch(MotionEvent.ACTION_UP, middle)
        assertEquals(emptyList<String>(), events)
    }

    private fun group(
        id: String,
        vararg members: String,
    ) = StripButton(id, R.drawable.ic_broom, ProofreadState.IDLE, members = buttons(*members))

    @Test
    fun `a group in the second row opens its popup while typing`() {
        strip.secondRow = true
        strip.customButtons = many(8) + group("g", "m")
        remeasure()
        strip.setSuggestions(listOf("один"))
        val second = (0 until strip.width).map { strip.customAt(it.toFloat(), 1.5f * row) }
        val x = second.indexOf(strip.customButtons.indexOfFirst { it.id == "g" })
        assertTrue(x >= 0)
        tap(x + 2f, 1.5f * row)
        assertEquals(emptyList<String>(), events)
        assertTrue(strip.groupShown)
    }

    @Test
    fun `a second tap on a group closes its popup`() {
        strip.customButtons = listOf(group("g", "m"))
        tap(1.5f * row)
        assertTrue(strip.groupShown)
        tap(1.5f * row)
        assertFalse(strip.groupShown)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `the popup of a group closes when the strip goes out of sight`() {
        strip.customButtons = listOf(group("g", "m"))
        tap(1.5f * row)
        strip.visibility = View.GONE
        assertFalse(strip.groupShown)
    }
}
