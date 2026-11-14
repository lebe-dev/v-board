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
import ru.tinyops.android.vboard.core.clipboard.ClipChip
import ru.tinyops.android.vboard.voice.VoiceState
import java.util.concurrent.TimeUnit

/** The menu a hold on the microphone opens: the finger slides to a mode and the release starts it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class SuggestionStripMicMenuTest {
    private val events = mutableListOf<String>()
    private lateinit var strip: SuggestionStripView

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        strip = SuggestionStripView(context, KeyboardTheme(context))
        strip.listener =
            object : SuggestionStripView.Listener {
                override fun onSuggestionPicked(word: String) = Unit

                override fun onSettingsClicked() = Unit

                override fun onClipboardClicked() = Unit

                override fun onFullscreenClicked() = Unit

                override fun onChipClicked(chip: ClipChip) = Unit

                override fun onMicUsed(modeId: String?) {
                    events += if (modeId == null) "mic" else "mode:$modeId"
                }

                override fun onVoiceCancel() = Unit

                override fun onProofreadClicked() = Unit

                override fun onProofreadHeld() = Unit

                override fun onCustomButtonUsed(
                    id: String,
                    held: Boolean,
                ) = Unit
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
    }

    private val row get() = KeyboardTheme(strip.context).suggestionHeight.toInt()

    private val mic get() = strip.width - strip.height / 2f

    private fun tap(x: Float) {
        touch(MotionEvent.ACTION_DOWN, x)
        touch(MotionEvent.ACTION_UP, x)
    }

    private fun touch(
        action: Int,
        x: Float,
        y: Float = row / 2f,
    ) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        strip.onTouchEvent(event)
        event.recycle()
    }

    private val modes = listOf(MicMenuItem("plain", "Без обработки"), MicMenuItem("improved", "С улучшением"))

    /** The middle of the row of mode [index] in the menu above the strip. */
    private fun modeY(index: Int) = -(modes.size - index - 0.5f) * row

    private fun holdMic() {
        touch(MotionEvent.ACTION_DOWN, mic)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `holding the microphone opens the menu and sliding to a mode starts it on release`() {
        strip.micMenu = modes
        holdMic()
        assertTrue(strip.micMenuShown)
        touch(MotionEvent.ACTION_MOVE, mic, modeY(0))
        touch(MotionEvent.ACTION_MOVE, mic, modeY(1))
        touch(MotionEvent.ACTION_UP, mic, modeY(1))
        assertEquals(listOf("mode:improved"), events)
        assertFalse(strip.micMenuShown)
    }

    @Test
    fun `the menu follows the finger and the first mode stands on top`() {
        strip.micMenu = modes
        holdMic()
        touch(MotionEvent.ACTION_MOVE, mic, modeY(1))
        touch(MotionEvent.ACTION_UP, mic, modeY(0))
        assertEquals(listOf("mode:plain"), events)
    }

    @Test
    fun `releasing outside the modes closes the menu and starts nothing`() {
        strip.micMenu = modes
        holdMic()
        touch(MotionEvent.ACTION_MOVE, mic, modeY(0))
        touch(MotionEvent.ACTION_UP, mic)
        assertEquals(emptyList<String>(), events)
        assertFalse(strip.micMenuShown)
        holdMic()
        touch(MotionEvent.ACTION_UP, mic, -(modes.size + 1) * row.toFloat())
        assertEquals(emptyList<String>(), events)
        holdMic()
        touch(MotionEvent.ACTION_UP, 0f, modeY(0))
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a cancelled touch closes the menu`() {
        strip.micMenu = modes
        holdMic()
        touch(MotionEvent.ACTION_CANCEL, mic, modeY(0))
        assertFalse(strip.micMenuShown)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a quick tap on the microphone with a menu stays a tap`() {
        strip.micMenu = modes
        tap(mic)
        ShadowLooper.idleMainLooper(HOLD_LONGER_MS, TimeUnit.MILLISECONDS)
        assertFalse(strip.micMenuShown)
        assertEquals(listOf("mic"), events)
    }

    @Test
    fun `without modes a hold on the microphone is a tap`() {
        holdMic()
        assertFalse(strip.micMenuShown)
        touch(MotionEvent.ACTION_UP, mic)
        assertEquals(listOf("mic"), events)
    }

    @Test
    fun `while dictating a hold on the microphone only ends the dictation`() {
        strip.micMenu = modes
        strip.voiceState = VoiceState(phase = VoiceState.Phase.LISTENING)
        holdMic()
        assertFalse(strip.micMenuShown)
        touch(MotionEvent.ACTION_UP, mic)
        assertEquals(listOf("mic"), events)
    }

    private companion object {
        const val HOLD_LONGER_MS = 500L
    }
}
