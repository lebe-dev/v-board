package ru.tinyops.android.vboard.settings

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.CustomButton

/** Drives the preview with real touch events. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class ButtonsPreviewDragTest {
    private val command = ButtonCommand(ButtonAction.COPY)
    private val buttons = listOf(CustomButton("a", command), CustomButton("b", command))
    private var arranged: List<CustomButton>? = null
    private val opened = ArrayList<String>()

    private fun show(
        list: List<CustomButton> = buttons,
        clipboard: ButtonPlacement = ButtonPlacement.RIGHT,
        fullscreen: ButtonPlacement? = null,
        widthScale: Float = 1f,
        secondRow: Boolean = false,
    ): View {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            // The action bar of the test activity covers the top of the window.
            Box(Modifier.padding(top = TOP.dp)) {
                ButtonsPreview(
                    list,
                    shape = StripShape(widthScale, secondRow, clipboard = clipboard, fullscreen = fullscreen),
                    names = { it.id },
                    onArrange = { arranged = it },
                    onOpen = { opened += it },
                )
            }
        }
        ShadowLooper.idleMainLooper()
        return activity.window.decorView.also {
            it.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            it.layout(0, 0, WIDTH, HEIGHT)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun View.touch(
        action: Int,
        x: Float,
        y: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y + TOP, 0)
        dispatchTouchEvent(event)
        event.recycle()
        ShadowLooper.idleMainLooper(FRAME_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun View.drag(
        fromX: Float,
        toX: Float,
        y: Float = ROW_Y,
        toY: Float = y,
    ) {
        touch(MotionEvent.ACTION_DOWN, fromX, y)
        val steps = 12
        for (step in 1..steps) {
            touch(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / steps, y + (toY - y) * step / steps)
        }
        touch(MotionEvent.ACTION_UP, toX, toY)
    }

    @Test
    fun `dragging a button past its neighbour swaps them`() {
        show().drag(fromX = 72f, toX = 130f)
        assertEquals(listOf("b", "a"), arranged?.map { it.id })
    }

    @Test
    fun `dragging a button across the strip changes its place`() {
        show().drag(fromX = 72f, toX = 275f)
        assertEquals(ButtonPlacement.RIGHT, arranged?.first { it.id == "a" }?.placement)
    }

    @Test
    fun `a tap opens the button and arranges nothing`() {
        val root = show()
        root.touch(MotionEvent.ACTION_DOWN, 72f, ROW_Y)
        root.touch(MotionEvent.ACTION_UP, 72f, ROW_Y)
        assertEquals(listOf("a"), opened)
        assertEquals(null, arranged)
    }

    @Test
    fun `the clipboard button keeps its place after the buttons, wherever it stands`() {
        val root = show(clipboard = ButtonPlacement.LEFT)
        root.touch(MotionEvent.ACTION_DOWN, 72f, ROW_Y)
        root.touch(MotionEvent.ACTION_UP, 72f, ROW_Y)
        root.touch(MotionEvent.ACTION_DOWN, 120f, ROW_Y)
        root.touch(MotionEvent.ACTION_UP, 120f, ROW_Y)
        root.touch(MotionEvent.ACTION_DOWN, 168f, ROW_Y)
        root.touch(MotionEvent.ACTION_UP, 168f, ROW_Y)
        assertEquals(listOf("a", "b"), opened)
    }

    @Test
    fun `the fullscreen button keeps its place after the clipboard button`() {
        val root = show(clipboard = ButtonPlacement.LEFT, fullscreen = ButtonPlacement.LEFT)
        for (x in listOf(72f, 120f, 168f, 216f)) {
            root.touch(MotionEvent.ACTION_DOWN, x, ROW_Y)
            root.touch(MotionEvent.ACTION_UP, x, ROW_Y)
        }
        assertEquals(listOf("a", "b"), opened)
    }

    @Test
    fun `the panel preview keeps room for the fixed panel buttons`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            Box(Modifier.padding(top = TOP.dp)) {
                FullscreenPanelPreview(
                    buttons,
                    names = { it.id },
                    onArrange = { arranged = it },
                    onOpen = { opened += it },
                )
            }
        }
        ShadowLooper.idleMainLooper()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, WIDTH, HEIGHT)
        ShadowLooper.idleMainLooper()
        // Three fixed buttons of 48 pixels on the left: the first free place starts at 144.
        root.touch(MotionEvent.ACTION_DOWN, 72f, ROW_Y)
        root.touch(MotionEvent.ACTION_UP, 72f, ROW_Y)
        assertEquals(emptyList<String>(), opened)
        root.touch(MotionEvent.ACTION_DOWN, 168f, ROW_Y)
        root.touch(MotionEvent.ACTION_UP, 168f, ROW_Y)
        assertEquals(listOf("a"), opened)
    }

    @Test
    fun `a button of the row below comes up to the strip`() {
        val list = ('a'..'f').map { CustomButton(it.toString(), command) }
        // Six buttons of 48 pixels in the 256 left between the cog and the buttons on the right: five fit.
        show(list).drag(fromX = 24f, toX = 72f, y = 120f, toY = ROW_Y)
        assertEquals("f", arranged?.first()?.id)
    }

    @Test
    fun `narrow buttons stand closer on the model`() {
        // At 60 percent a button is 28.8 pixels wide and the first one starts after the cog at 48.
        val root = show(widthScale = 0.6f)
        for (x in listOf(62f, 91f)) {
            root.touch(MotionEvent.ACTION_DOWN, x, ROW_Y)
            root.touch(MotionEvent.ACTION_UP, x, ROW_Y)
        }
        assertEquals(listOf("a", "b"), opened)
    }

    @Test
    fun `a button of the second row comes up to the strip`() {
        val list = ('a'..'h').map { CustomButton(it.toString(), command) }
        // Five fit on the strip; f starts the second row right under it.
        show(list, secondRow = true).drag(fromX = 24f, toX = 72f, y = 72f, toY = -ROW_Y)
        assertEquals("f", arranged?.first()?.id)
    }

    @Test
    fun `a button of the strip goes down to the second row`() {
        val list = ('a'..'h').map { CustomButton(it.toString(), command) }
        show(list, secondRow = true).drag(fromX = 72f, toX = 0f, toY = 120f)
        assertEquals(listOf("b", "c", "d", "e", "f", "a", "g", "h"), arranged?.map { it.id })
    }

    private companion object {
        const val WIDTH = 400
        const val TOP = 200
        const val HEIGHT = 800
        const val ROW_Y = 24f
        const val FRAME_MS = 16L
    }
}
