package ru.tinyops.android.vboard.ui

import android.view.MotionEvent
import android.view.View
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
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.voice.ProofreadState
import ru.tinyops.android.vboard.voice.VoiceState
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class SuggestionStripTouchTest {
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

                override fun onSettingsClicked() {
                    events += "settings"
                }

                override fun onClipboardClicked() {
                    events += "clipboard"
                }

                override fun onFullscreenClicked() {
                    events += "fullscreen"
                }

                override fun onChipClicked(chip: ClipChip) {
                    events += "chip:${chip.label}"
                }

                override fun onMicClicked() {
                    events += "mic"
                }

                override fun onVoiceCancel() {
                    events += "cancel"
                }

                override fun onProofreadClicked() {
                    events += "proofread"
                }

                override fun onProofreadHeld() {
                    events += "proofread-hold"
                }

                override fun onCustomButtonUsed(
                    id: String,
                    held: Boolean,
                ) {
                    events += if (held) "hold:$id" else "custom:$id"
                }
            }
        // Delayed work of a view runs only while it is attached to a window.
        Robolectric
            .buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
            .setContentView(strip)
        ShadowLooper.idleMainLooper()
        val width = context.resources.displayMetrics.widthPixels
        strip.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
        strip.undoPeek.onPeek = { id, peek -> events += if (peek) "peek:$id" else "unpeek:$id" }
    }

    private fun remeasure() {
        strip.measure(
            View.MeasureSpec.makeMeasureSpec(strip.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
    }

    /** The custom buttons that can be tapped along a row of the strip. */
    private fun customsIn(y: Float = row / 2f) =
        (0 until strip.width)
            .map { strip.customAt(it.toFloat(), y) }
            .filter { it >= 0 }
            .toSet()
            .size

    private val row get() = KeyboardTheme(strip.context).suggestionHeight.toInt()

    private fun tap(
        x: Float,
        y: Float = row / 2f,
    ) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            strip.onTouchEvent(event)
            event.recycle()
        }
    }

    private val leftButton get() = strip.height / 2f
    private val mic get() = strip.width - strip.height / 2f
    private val clipboard get() = strip.width - 1.5f * strip.height
    private val middle get() = strip.width / 2f

    @Test
    fun `cog opens settings and microphone starts dictation`() {
        tap(leftButton)
        tap(mic)
        assertEquals(listOf("settings", "mic"), events)
    }

    private fun buttons(vararg ids: String) = ids.map { StripButton(it, R.drawable.ic_broom, ProofreadState.IDLE) }

    private fun press(
        x: Float,
        action: Int,
    ) {
        val event = MotionEvent.obtain(0, 0, action, x, row / 2f, 0)
        strip.onTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `holding a button uses its hold action once and the release is no tap`() {
        strip.customButtons = listOf(buttons("a").single().copy(holds = true))
        val x = 1.5f * strip.height
        press(x, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        press(x, MotionEvent.ACTION_UP)
        assertEquals(listOf("hold:a"), events)
    }

    @Test
    fun `holding the undo arrow peeks at the old text until the finger lets go`() {
        strip.customButtons = listOf(buttons("a").single().copy(state = ProofreadState.UNDO, holds = true))
        val x = 1.5f * strip.height
        press(x, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        assertEquals(listOf("peek:a"), events)
        press(x, MotionEvent.ACTION_UP)
        assertEquals(listOf("peek:a", "unpeek:a"), events)
    }

    @Test
    fun `a quick tap on the undo arrow stays a tap`() {
        strip.customButtons = listOf(buttons("a").single().copy(state = ProofreadState.UNDO, holds = true))
        tap(1.5f * strip.height)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        assertEquals(listOf("custom:a"), events)
    }

    @Test
    fun `a quick tap on a button with a hold action stays a tap`() {
        strip.customButtons = listOf(buttons("a").single().copy(holds = true))
        tap(1.5f * strip.height)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        assertEquals(listOf("custom:a"), events)
    }

    @Test
    fun `a button without a hold action taps however long it is pressed`() {
        strip.customButtons = buttons("a")
        val x = 1.5f * strip.height
        press(x, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        press(x, MotionEvent.ACTION_UP)
        assertEquals(listOf("custom:a"), events)
    }

    @Test
    fun `buttons stand where they are placed`() {
        strip.customButtons =
            listOf(
                buttons("l").single(),
                buttons("c").single().copy(placement = ButtonPlacement.CENTER),
                buttons("r").single().copy(placement = ButtonPlacement.RIGHT),
            )
        // The clipboard button comes last on the right, before the microphone.
        val right = strip.width - 2 * strip.height
        tap(1.5f * strip.height)
        tap((strip.height + right) / 2f)
        tap(right - strip.height / 2f)
        assertEquals(listOf("custom:l", "custom:c", "custom:r"), events)
    }

    @Test
    fun `suggestions go away after the timeout and the buttons come back`() {
        strip.suggestionTimeoutMillis = TIMEOUT_MS
        strip.customButtons = listOf(buttons("a").single().copy())
        strip.setSuggestions(listOf("один"))
        assertTrue(!strip.zoneAt(1.5f * strip.height).name.startsWith("CUSTOM"))
        ShadowLooper.idleMainLooper(TIMEOUT_MS + 1, TimeUnit.MILLISECONDS)
        tap(1.5f * strip.height)
        assertEquals(listOf("custom:a"), events)
    }

    @Test
    fun `every update of the suggestions starts the timeout again`() {
        strip.suggestionTimeoutMillis = TIMEOUT_MS
        strip.setSuggestions(listOf("один"))
        ShadowLooper.idleMainLooper(TIMEOUT_MS - 1, TimeUnit.MILLISECONDS)
        strip.setSuggestions(listOf("один"))
        ShadowLooper.idleMainLooper(TIMEOUT_MS - 1, TimeUnit.MILLISECONDS)
        tap(middle)
        assertEquals(listOf("pick:один"), events)
    }

    @Test
    fun `a finger on a suggestion keeps it past the timeout`() {
        strip.suggestionTimeoutMillis = TIMEOUT_MS
        strip.setSuggestions(listOf("один"))
        press(middle, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(TIMEOUT_MS + 1, TimeUnit.MILLISECONDS)
        press(middle, MotionEvent.ACTION_UP)
        assertEquals(listOf("pick:один"), events)
    }

    @Test
    fun `without a timeout suggestions stay`() {
        strip.setSuggestions(listOf("один"))
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS * 100, TimeUnit.MILLISECONDS)
        tap(middle)
        assertEquals(listOf("pick:один"), events)
    }

    @Test
    fun `custom buttons follow the cog and act on tap`() {
        strip.customButtons = buttons("a", "b")
        tap(1.5f * strip.height)
        tap(2.5f * strip.height)
        assertEquals(listOf("custom:a", "custom:b"), events)
    }

    @Test
    fun `custom buttons give way to suggestions and to dictation`() {
        strip.customButtons = buttons("a")
        strip.setSuggestions(listOf("один"))
        assertFalse(strip.zoneAt(0.5f * strip.height).name.startsWith("CUSTOM"))
        strip.setSuggestions(emptyList())
        strip.voiceState = VoiceState(phase = VoiceState.Phase.LISTENING)
        assertEquals(SuggestionStripView.Zone.NONE, strip.zoneAt(1.5f * strip.height))
    }

    @Test
    fun `only the custom buttons that fit are shown`() {
        strip.customButtons = buttons("a", "b", "c", "d", "e", "f")
        val fit = (strip.width - 3 * strip.height) / strip.height
        assertEquals(minOf(fit, 6), customsIn())
    }

    @Test
    fun `chips share the strip with few custom buttons and hide before many`() {
        strip.setClipChips(listOf(ClipChip(ClipKind.TEXT, "Привет", "Привет")))
        strip.customButtons = buttons("a")
        val area = 2 * strip.height until strip.width - 2 * strip.height
        assertTrue(area.any { strip.zoneAt(it.toFloat()) == SuggestionStripView.Zone.CHIP_0 })
        strip.customButtons = buttons("a", "b", "c", "d", "e", "f")
        assertTrue(area.none { strip.zoneAt(it.toFloat()) == SuggestionStripView.Zone.CHIP_0 })
    }

    @Test
    fun `clipboard button follows the chosen place`() {
        strip.clipboardPlacement = ButtonPlacement.LEFT
        tap(1.5f * strip.height)
        strip.clipboardPlacement = ButtonPlacement.CENTER
        tap(middle)
        assertEquals(listOf("clipboard", "clipboard"), events)
        assertEquals(SuggestionStripView.Zone.NONE, strip.zoneAt(strip.width - 1.5f * strip.height))
    }

    @Test
    fun `clipboard button keeps its place beside custom buttons`() {
        strip.clipboardPlacement = ButtonPlacement.LEFT
        strip.customButtons = buttons("a")
        tap(1.5f * strip.height)
        tap(2.5f * strip.height)
        assertEquals(listOf("custom:a", "clipboard"), events)
    }

    @Test
    fun `without the clipboard button its place goes to the others`() {
        strip.clipboardPlacement = null
        val zones = (0 until strip.width).map { strip.zoneAt(it.toFloat()) }
        assertFalse(SuggestionStripView.Zone.CLIPBOARD in zones)
        strip.fullscreenPlacement = ButtonPlacement.LEFT
        strip.customButtons = buttons("a")
        tap(1.5f * strip.height)
        tap(2.5f * strip.height)
        assertEquals(listOf("custom:a", "fullscreen"), events)
    }

    @Test
    fun `without the clipboard button one more custom button fits`() {
        strip.customButtons = buttons("a", "b", "c", "d", "e", "f")
        val before = customsIn()
        strip.clipboardPlacement = null
        assertEquals(minOf(before + 1, 6), customsIn())
    }

    private fun fullscreenShown() =
        SuggestionStripView.Zone.FULLSCREEN in (0 until strip.width).map { strip.zoneAt(it.toFloat()) }

    @Test
    fun `the fullscreen button is absent until it is given a place`() {
        assertFalse(fullscreenShown())
    }

    @Test
    fun `the fullscreen button stands in the chosen place`() {
        strip.fullscreenPlacement = ButtonPlacement.LEFT
        tap(1.5f * strip.height)
        strip.fullscreenPlacement = ButtonPlacement.CENTER
        tap(middle)
        assertEquals(listOf("fullscreen", "fullscreen"), events)
    }

    @Test
    fun `the fullscreen button comes after the custom buttons and the clipboard button`() {
        strip.clipboardPlacement = ButtonPlacement.LEFT
        strip.fullscreenPlacement = ButtonPlacement.LEFT
        strip.customButtons = buttons("a")
        tap(1.5f * strip.height)
        tap(2.5f * strip.height)
        tap(3.5f * strip.height)
        assertEquals(listOf("custom:a", "clipboard", "fullscreen"), events)
    }

    @Test
    fun `the fullscreen button leaves a custom button out when the strip is full`() {
        strip.customButtons = buttons("a", "b", "c", "d", "e", "f")
        val before = customsIn()
        strip.fullscreenPlacement = ButtonPlacement.LEFT
        assertEquals(before - 1, customsIn())
    }

    @Test
    fun `the fullscreen button gives way to suggestions and to dictation`() {
        strip.fullscreenPlacement = ButtonPlacement.LEFT
        strip.setSuggestions(listOf("один"))
        assertFalse(fullscreenShown())
        strip.clear()
        strip.voiceState = VoiceState(VoiceState.Phase.LISTENING)
        assertFalse(fullscreenShown())
    }

    @Test
    fun `clipboard button sits left of the microphone`() {
        tap(clipboard)
        assertEquals(listOf("clipboard"), events)
    }

    @Test
    fun `chips are centered between the buttons and paste on tap`() {
        val chips = listOf(ClipChip(ClipKind.TEXT, "Привет", "Привет"), ClipChip(ClipKind.LINK, "vast.ai", "vast.ai"))
        strip.setClipChips(chips)
        val area = strip.height until strip.width - 2 * strip.height
        val first = area.first { strip.zoneAt(it.toFloat()) == SuggestionStripView.Zone.CHIP_0 }
        val last = area.last { strip.zoneAt(it.toFloat()) == SuggestionStripView.Zone.CHIP_1 }
        val leftMargin = first - strip.height
        val rightMargin = (strip.width - 2 * strip.height) - 1 - last
        assertTrue("margins $leftMargin and $rightMargin differ", abs(leftMargin - rightMargin) <= 2)
        tap(first.toFloat())
        tap(last.toFloat())
        assertEquals(listOf("chip:Привет", "chip:vast.ai"), events)
    }

    @Test
    fun `chips give way to suggestions and to dictation`() {
        strip.setClipChips(listOf(ClipChip(ClipKind.TEXT, "Привет", "Привет")))
        assertEquals(SuggestionStripView.Zone.CHIP_0, strip.zoneAt(middle))
        strip.setSuggestions(listOf("hello", "help", "hell"))
        assertEquals(SuggestionStripView.Zone.SLOT_MIDDLE, strip.zoneAt(middle))
        strip.clear()
        strip.voiceState = VoiceState(VoiceState.Phase.LISTENING)
        assertEquals(SuggestionStripView.Zone.NONE, strip.zoneAt(middle))
    }

    @Test
    fun `space beside the chips does nothing`() {
        strip.setClipChips(listOf(ClipChip(ClipKind.LINK, "a.io", "a.io")))
        tap(strip.height * 1.2f)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `suggestions take the room of the cog and the clipboard button while typing`() {
        strip.setSuggestions(listOf("hello", "help", "hell"))
        val slot = (strip.width - strip.height) / 3f
        tap(middle)
        tap(slot / 2f)
        tap(strip.width - strip.height - slot / 2f)
        assertEquals(listOf("pick:hello", "pick:help", "pick:hell"), events)
    }

    @Test
    fun `the microphone stays while typing`() {
        strip.setSuggestions(listOf("hello", "help", "hell"))
        tap(mic)
        assertEquals(listOf("mic"), events)
    }

    @Test
    fun `cog and clipboard button come back when suggestions go away`() {
        strip.setSuggestions(listOf("hello"))
        strip.clear()
        tap(leftButton)
        tap(clipboard)
        assertEquals(listOf("settings", "clipboard"), events)
    }

    @Test
    fun `empty slots and a hidden microphone do nothing, paste moves to the edge`() {
        strip.voiceAvailable = false
        tap(middle)
        assertEquals(emptyList<String>(), events)
        assertEquals(SuggestionStripView.Zone.CLIPBOARD, strip.zoneAt(mic))
        tap(mic)
        assertEquals(listOf("clipboard"), events)
    }

    @Test
    fun `while dictating the left button cancels and suggestions are inactive`() {
        strip.setSuggestions(listOf("hello", "help", "hell"))
        strip.voiceState = VoiceState(VoiceState.Phase.LISTENING, preview = "Привет")
        tap(middle)
        tap(clipboard)
        tap(leftButton)
        tap(mic)
        assertEquals(listOf("cancel", "mic"), events)
    }

    @Test
    fun `dragging off a button cancels the tap`() {
        val y = strip.height / 2f
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, mic, y, 0)
        val up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, middle, y, 0)
        strip.onTouchEvent(down)
        strip.onTouchEvent(up)
        down.recycle()
        up.recycle()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `the proofread button sits left of the microphone`() {
        strip.proofreadState = ProofreadState.IDLE
        val button = strip.height.toFloat()
        val width = strip.width.toFloat()
        assertEquals(SuggestionStripView.Zone.MIC, strip.zoneAt(width - button / 2))
        assertEquals(SuggestionStripView.Zone.PROOFREAD, strip.zoneAt(width - button * 1.5f))
        assertEquals(SuggestionStripView.Zone.CLIPBOARD, strip.zoneAt(width - button * 2.5f))

        tap(width - button * 1.5f)
        assertEquals(listOf("proofread"), events)
    }

    @Test
    fun `holding the proofread button uses its hold action once and the release is no tap`() {
        strip.proofreadState = ProofreadState.IDLE
        strip.proofreadHolds = true
        val x = strip.width - strip.height * 1.5f
        press(x, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        press(x, MotionEvent.ACTION_UP)
        assertEquals(listOf("proofread-hold"), events)
    }

    @Test
    fun `a quick tap on the proofread button with a hold action stays a tap`() {
        strip.proofreadState = ProofreadState.IDLE
        strip.proofreadHolds = true
        val x = strip.width - strip.height * 1.5f
        tap(x)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        assertEquals(listOf("proofread"), events)
    }

    @Test
    fun `the proofread button without a hold action taps however long it is pressed`() {
        strip.proofreadState = ProofreadState.IDLE
        val x = strip.width - strip.height * 1.5f
        press(x, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        press(x, MotionEvent.ACTION_UP)
        assertEquals(listOf("proofread"), events)
    }

    @Test
    fun `holding the proofread undo arrow peeks until the finger lets go and is no tap`() {
        strip.proofreadState = ProofreadState.UNDO
        val x = strip.width - strip.height * 1.5f
        press(x, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        press(x, MotionEvent.ACTION_UP)
        assertEquals(listOf("peek:proofread", "unpeek:proofread"), events)
    }

    @Test
    fun `a running proofread button taps to cancel even with a hold action`() {
        strip.proofreadState = ProofreadState.WORKING
        strip.proofreadHolds = true
        val x = strip.width - strip.height * 1.5f
        press(x, MotionEvent.ACTION_DOWN)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        press(x, MotionEvent.ACTION_UP)
        assertEquals(listOf("proofread"), events)
    }

    @Test
    fun `without the proofread button the strip stays as it was`() {
        val button = strip.height.toFloat()
        val width = strip.width.toFloat()
        assertEquals(SuggestionStripView.Zone.CLIPBOARD, strip.zoneAt(width - button * 1.5f))
    }

    @Test
    fun `the proofread button gives its room to the slots while typing`() {
        strip.setSuggestions(listOf("a", "b", "c"))
        strip.proofreadState = ProofreadState.IDLE
        val button = strip.height.toFloat()
        val width = strip.width.toFloat()
        assertEquals(SuggestionStripView.Zone.SLOT_RIGHT, strip.zoneAt(width - button * 1.5f))
    }

    @Test
    fun `a running proofread stays on the strip while typing`() {
        strip.setSuggestions(listOf("a", "b", "c"))
        strip.proofreadState = ProofreadState.WORKING
        val button = strip.height.toFloat()
        val width = strip.width.toFloat()
        assertEquals(SuggestionStripView.Zone.PROOFREAD, strip.zoneAt(width - button * 1.5f))
        assertEquals(SuggestionStripView.Zone.SLOT_RIGHT, strip.zoneAt(width - button * 2.5f))
    }

    @Test
    fun `dictation hides the proofread button`() {
        strip.proofreadState = ProofreadState.IDLE
        strip.voiceState = VoiceState(phase = VoiceState.Phase.LISTENING)
        val button = strip.height.toFloat()
        assertEquals(SuggestionStripView.Zone.NONE, strip.zoneAt(strip.width - button * 1.5f))
    }

    private fun many(count: Int) = buttons(*Array(count) { "b$it" })

    @Test
    fun `narrower buttons stand closer and more of them fit`() {
        strip.customButtons = many(20)
        val wide = customsIn()
        strip.buttonWidthScale = 0.7f
        val narrow = row * 0.7f
        tap(row + narrow * 0.5f)
        tap(row + narrow * 1.5f)
        assertEquals(listOf("custom:b0", "custom:b1"), events)
        // Between the cog and the microphone, where the clipboard button keeps its place.
        val fit = ((strip.width - 2 * row) / narrow).toInt() - 1
        assertEquals(fit, customsIn())
        assertTrue(customsIn() > wide)
    }

    @Test
    fun `the cog and the microphone keep their width`() {
        strip.buttonWidthScale = 0.6f
        tap(leftButton)
        tap(mic)
        assertEquals(listOf("settings", "mic"), events)
    }

    @Test
    fun `the buttons that do not fit go to the second row`() {
        strip.secondRow = true
        strip.customButtons = many(20)
        remeasure()
        assertEquals(2 * row, strip.height)
        val first = customsIn()
        assertTrue(customsIn(1.5f * row) > 0)
        tap(row / 2f, 1.5f * row)
        assertEquals(listOf("custom:b$first"), events)
    }

    @Test
    fun `hidden buttons leave the strip, those in the second row stay`() {
        strip.secondRow = true
        strip.customButtons = many(20).map { it.copy(hidden = true) }
        remeasure()
        val onStrip = strip.customButtons.size
        assertEquals(0, customsIn())
        assertTrue(customsIn(1.5f * row) > 0)
        assertTrue(onStrip in 1..19)
        tap(row / 2f, 1.5f * row)
        assertEquals(1, events.size)
    }

    @Test
    fun `hidden buttons are not shown on a single row`() {
        strip.customButtons = many(2).map { it.copy(hidden = true) }
        remeasure()
        assertEquals(0, customsIn())
        assertEquals(emptyList<StripButton>(), strip.customButtons)
    }

    @Test
    fun `without a second row the strip keeps one row`() {
        strip.customButtons = many(20)
        remeasure()
        assertEquals(row, strip.height)
        assertEquals(0, customsIn(1.5f * row))
    }

    @Test
    fun `the second row is left out while every button fits on the strip`() {
        strip.secondRow = true
        strip.customButtons = many(2)
        remeasure()
        assertEquals(row, strip.height)
    }

    @Test
    fun `the second row is kept for the buttons the field may show later`() {
        strip.secondRow = true
        strip.plannedButtons = 20
        remeasure()
        assertEquals(2 * row, strip.height)
    }

    @Test
    fun `the second row stays while typing and goes quiet while dictating`() {
        strip.secondRow = true
        strip.customButtons = many(20)
        remeasure()
        strip.setSuggestions(listOf("один"))
        assertTrue(customsIn(1.5f * row) > 0)
        assertEquals(0, customsIn())
        strip.clear()
        strip.voiceState = VoiceState(phase = VoiceState.Phase.LISTENING)
        assertEquals(0, customsIn(1.5f * row))
        remeasure()
        assertEquals(2 * row, strip.height)
    }

    private companion object {
        const val HOLD_LONGER_MS = 500L
        const val TIMEOUT_MS = 1_000L
    }
}
