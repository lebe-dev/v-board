package ru.tinyops.android.vboard.ui

import android.inputmethodservice.ExtractEditText
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.voice.ProofreadState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class FullscreenEditorViewTest {
    private val events = ArrayList<String>()
    private lateinit var view: FullscreenEditorView

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        view = FullscreenEditorView(context, KeyboardTheme(context))
        view.listener =
            object : FullscreenEditorView.Listener {
                override fun onEditorSelectAll() {
                    events += "select"
                }

                override fun onEditorUndo() {
                    events += "undo"
                }

                override fun onEditorCopy(text: CharSequence) {
                    events += "copy:$text"
                }

                override fun onEditorClose() {
                    events += "close"
                }

                override fun onEditorButton(
                    id: String,
                    held: Boolean,
                ) {
                    events += if (held) "hold:$id" else "tap:$id"
                }
            }
    }

    private val editor get() = view.findViewById<ExtractEditText>(android.R.id.inputExtractEditText)

    private fun button(description: Int): View {
        val found = ArrayList<View>()
        val label = view.context.getString(description)
        view.findViewsWithText(found, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return found.single()
    }

    private fun custom(
        id: String,
        placement: ButtonPlacement = ButtonPlacement.LEFT,
        holds: Boolean = false,
        state: ProofreadState = ProofreadState.IDLE,
    ) = StripButton(id, R.drawable.ic_broom, state, placement = placement, holds = holds, description = "custom-$id")

    private fun customView(id: String): View {
        val found = ArrayList<View>()
        view.findViewsWithText(found, "custom-$id", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return found.single()
    }

    /** Lays the panel out and tells the left edge of a button inside the view. */
    private fun leftOf(id: String): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(PANEL_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PANEL_WIDTH, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, PANEL_WIDTH, PANEL_WIDTH)
        val button = customView(id)
        return (button.parent as View).left + button.left
    }

    @Test
    fun `custom buttons stand by their places between the fixed ones`() {
        view.customButtons =
            listOf(
                custom("r", ButtonPlacement.RIGHT),
                custom("l", ButtonPlacement.LEFT),
                custom("c", ButtonPlacement.CENTER),
            )
        val size = KeyboardTheme(view.context).suggestionHeight.toInt()
        val roomStart = 3 * size
        val roomEnd = PANEL_WIDTH - size
        assertEquals(roomStart, leftOf("l"))
        assertEquals(roomEnd - size, leftOf("r"))
        assertEquals((roomStart + roomEnd - size) / 2, leftOf("c"))
    }

    @Test
    fun `a custom button that does not fit takes no room`() {
        view.customButtons = (1..6).map { custom("b$it") }
        leftOf("b1")
        assertTrue(customView("b4").width > 0)
        assertEquals(0, customView("b5").width)
        assertEquals(0, customView("b6").width)
    }

    @Test
    fun `gestures on a custom button reach the listener`() {
        view.customButtons = listOf(custom("a", holds = true), custom("b"))
        customView("a").performClick()
        customView("a").performLongClick()
        customView("b").performClick()
        assertFalse(customView("b").isLongClickable)
        assertEquals(listOf("tap:a", "hold:a", "tap:b"), events)
    }

    @Test
    fun `holding an undo arrow previews the undo instead of acting`() {
        val asked = ArrayList<String>()
        view.undoPreview.source = {
            asked += it
            "прежний текст"
        }
        view.customButtons = listOf(custom("a", state = ProofreadState.UNDO, holds = true))
        customView("a").performLongClick()
        assertEquals(listOf("a"), asked)
        assertEquals(emptyList<String>(), events)
        customView("a").performClick()
        assertEquals(listOf("tap:a"), events)
    }

    @Test
    fun `holding the panel undo previews the undo and a tap still undoes`() {
        val asked = ArrayList<String>()
        view.undoPreview.source = {
            asked += it
            "прежний текст"
        }
        button(R.string.fullscreen_undo).performLongClick()
        assertEquals(listOf(FullscreenEditorView.UNDO_ID), asked)
        assertEquals(emptyList<String>(), events)
        button(R.string.fullscreen_undo).performClick()
        assertEquals(listOf("undo"), events)
    }

    @Test
    fun `a custom button shows its state`() {
        view.customButtons = listOf(custom("a"))
        val idle = (customView("a") as ImageView).drawable
        view.customButtons = listOf(custom("a", state = ProofreadState.WORKING))
        assertTrue((customView("a") as ImageView).drawable is SpinnerDrawable)
        view.customButtons = listOf(custom("a", state = ProofreadState.UNDO))
        assertNotSame(idle, (customView("a") as ImageView).drawable)
        assertFalse((customView("a") as ImageView).drawable is SpinnerDrawable)
    }

    @Test
    fun `the system finds its text field by the reserved id`() {
        assertNotNull(editor)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, editor.layoutParams.width)
    }

    @Test
    fun `panel buttons reach the listener`() {
        button(R.string.fullscreen_select_all).performClick()
        button(R.string.fullscreen_undo).performClick()
        button(R.string.fullscreen_close).performClick()
        assertEquals(listOf("select", "undo", "close"), events)
    }

    @Test
    fun `copy takes the selection, or the whole text without one`() {
        editor.setText("один два")
        button(R.string.fullscreen_copy).performClick()
        editor.setSelection(0, 4)
        button(R.string.fullscreen_copy).performClick()
        assertEquals(listOf("copy:один два", "copy:один"), events)
    }

    private companion object {
        const val PANEL_WIDTH = 1078
    }
}
