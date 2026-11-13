package ru.tinyops.android.vboard.ui

import android.os.SystemClock
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
import org.robolectric.shadows.ShadowLooper
import ru.tinyops.android.vboard.core.input.DeleteHoldMode
import ru.tinyops.android.vboard.core.input.SpaceDragMode
import ru.tinyops.android.vboard.core.layout.KeySpec
import ru.tinyops.android.vboard.core.layout.KeyType
import ru.tinyops.android.vboard.core.layout.KeyboardGeometry
import ru.tinyops.android.vboard.core.layout.KeyboardLayouts
import ru.tinyops.android.vboard.core.layout.KeyboardMode
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class KeyboardViewTouchTest {
    private val events = mutableListOf<String>()
    private var gesture: List<TouchPoint> = emptyList()
    private val liveUpdates = mutableListOf<List<TouchPoint>>()
    private lateinit var view: KeyboardView
    private var downTime = 0L
    private var movedChars = 0
    private var movedLines = 0

    private val space = KeyType.SPACE.name

    /** Pixels for a distance in dp; the test screen has a density of 2.625. */
    private fun dp(value: Float): Float = value * view.resources.displayMetrics.density

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        view = KeyboardView(context, KeyboardTheme(context))
        view.spec = KeyboardLayouts.spec(Language.ENGLISH, KeyboardMode.LETTERS)
        view.gestureEnabled = true
        view.listener =
            object : KeyboardView.Listener {
                override fun onKey(key: KeySpec) {
                    events += "key:${key.type}:${key.output}"
                }

                override fun onDelete(step: DeleteHoldMode) {
                    events += "delete:$step"
                }

                override fun onAlternate(text: String) {
                    events += "alt:$text"
                }

                override fun onEmojiRequested() {
                    events += "emoji"
                }

                override fun onShiftLongPress() {
                    events += "shiftLock"
                }

                override fun onLanguageLongPress() {
                    events += "picker"
                }

                override fun onGestureStart() {
                    events += "gestureStart"
                }

                override fun onGestureUpdate(points: List<TouchPoint>) {
                    liveUpdates += points
                }

                override fun onGestureEnd(points: List<TouchPoint>) {
                    events += "gestureEnd"
                    gesture = points
                }

                override fun onGeometryChanged(geometry: KeyboardGeometry) = Unit

                override fun onSpaceDragStart() {
                    events += "dragStart"
                }

                override fun onSpaceDragMove(
                    chars: Int,
                    lines: Int,
                ) {
                    events += "drag:$chars:$lines"
                    movedChars += chars
                    movedLines += lines
                }

                override fun onSpaceDragEnd() {
                    events += "dragEnd"
                }
            }
        val width = context.resources.displayMetrics.widthPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        downTime = SystemClock.uptimeMillis()
    }

    private fun center(output: String): Pair<Float, Float> {
        val key = view.geometry!!.keys.first { it.spec.output == output || it.spec.type.name == output }
        return key.bounds.centerX to key.bounds.centerY
    }

    private fun touch(
        action: Int,
        point: Pair<Float, Float>,
        offsetMillis: Long = 0,
    ) {
        val event = MotionEvent.obtain(downTime, downTime + offsetMillis, action, point.first, point.second, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `tap fires the key on release`() {
        touch(MotionEvent.ACTION_DOWN, center("q"))
        assertTrue(events.isEmpty())
        touch(MotionEvent.ACTION_UP, center("q"), 50)
        assertEquals(listOf("key:CHARACTER:q"), events)
    }

    @Test
    fun `small jitter on a key is still a tap`() {
        val (x, y) = center("g")
        touch(MotionEvent.ACTION_DOWN, x to y)
        touch(MotionEvent.ACTION_MOVE, x + 8f to y + 5f, 20)
        touch(MotionEvent.ACTION_UP, x + 8f to y + 5f, 40)
        assertEquals(listOf("key:CHARACTER:g"), events)
    }

    @Test
    fun `sliding across letters is a gesture, not a tap`() {
        touch(MotionEvent.ACTION_DOWN, center("h"))
        var t = 0L
        for (letter in listOf("e", "l", "o")) {
            t += 60
            touch(MotionEvent.ACTION_MOVE, center(letter), t)
        }
        touch(MotionEvent.ACTION_UP, center("o"), t + 20)
        assertEquals(listOf("gestureStart", "gestureEnd"), events)
        assertEquals(TouchAction.DOWN, gesture.first().action)
        assertEquals(TouchAction.UP, gesture.last().action)
        assertEquals(center("o").first, gesture.last().x, 0.01f)
    }

    @Test
    fun `gesture updates share one list and the final gesture is a stable snapshot`() {
        touch(MotionEvent.ACTION_DOWN, center("h"))
        var t = 0L
        for (letter in listOf("e", "l", "o", "k")) {
            t += 60
            touch(MotionEvent.ACTION_MOVE, center(letter), t)
        }
        assertTrue("updates=${liveUpdates.size}", liveUpdates.size >= 2)
        assertTrue(liveUpdates.all { it === liveUpdates.first() })

        touch(MotionEvent.ACTION_UP, center("k"), t + 20)
        val finalSize = gesture.size
        touch(MotionEvent.ACTION_DOWN, center("q"), t + 40)
        assertEquals(finalSize, gesture.size)
        assertEquals(TouchAction.UP, gesture.last().action)
    }

    @Test
    fun `long press offers the digit alternate`() {
        touch(MotionEvent.ACTION_DOWN, center("w"))
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_UP, center("w"), 450)
        assertEquals(listOf("alt:2"), events)
    }

    @Test
    fun `holding the comma and letting go opens the emoji picker`() {
        touch(MotionEvent.ACTION_DOWN, center(","))
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_UP, center(","), 450)
        assertEquals(listOf("emoji"), events)
    }

    @Test
    fun `tapping the comma still types it`() {
        touch(MotionEvent.ACTION_DOWN, center(","))
        touch(MotionEvent.ACTION_UP, center(","), 50)
        assertEquals(listOf("key:CHARACTER:,"), events)
    }

    @Test
    fun `backspace fires on press and repeats while held`() {
        touch(MotionEvent.ACTION_DOWN, center(KeyType.BACKSPACE.name))
        ShadowLooper.idleMainLooper(400 + 50 * 3, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_UP, center(KeyType.BACKSPACE.name), 600)
        assertTrue("events=$events", events.size >= 4 && events.all { it == "delete:CHARACTERS" })
    }

    @Test
    fun `holding delete in the word mode deletes words after the first character`() {
        view.deleteHold = DeleteHoldMode.WORDS
        holdBackspace(400 + 150 * 2)
        assertEquals("delete:CHARACTERS", events.first())
        assertTrue("events=$events", events.size >= 3 && events.drop(1).all { it == "delete:WORDS" })
    }

    @Test
    fun `holding delete in the clear mode deletes everything once`() {
        view.deleteHold = DeleteHoldMode.ALL
        holdBackspace(2000)
        assertEquals(listOf("delete:CHARACTERS", "delete:ALL"), events)
    }

    @Test
    fun `a short press in the clear mode deletes one character`() {
        view.deleteHold = DeleteHoldMode.ALL
        holdBackspace(300)
        assertEquals(listOf("delete:CHARACTERS"), events)
    }

    @Test
    fun `holding delete does nothing more when the hold is off`() {
        view.deleteHold = DeleteHoldMode.NONE
        holdBackspace(2000)
        assertEquals(listOf("delete:CHARACTERS"), events)
    }

    private fun holdBackspace(millis: Long) {
        touch(MotionEvent.ACTION_DOWN, center(KeyType.BACKSPACE.name))
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_UP, center(KeyType.BACKSPACE.name), millis + 10)
    }

    @Test
    fun `long press on shift locks it`() {
        touch(MotionEvent.ACTION_DOWN, center(KeyType.SHIFT.name))
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_UP, center(KeyType.SHIFT.name), 450)
        assertEquals(listOf("shiftLock"), events)
    }

    @Test
    fun `a tap on the space bar types a space`() {
        touch(MotionEvent.ACTION_DOWN, center(space))
        touch(MotionEvent.ACTION_UP, center(space), 50)
        assertEquals(listOf("key:SPACE: "), events)
    }

    @Test
    fun `jitter on the space bar is still a space`() {
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        touch(MotionEvent.ACTION_MOVE, x + dp(6f) to y + dp(6f), 20)
        touch(MotionEvent.ACTION_UP, x + dp(6f) to y + dp(6f), 40)
        assertEquals(listOf("key:SPACE: "), events)
    }

    @Test
    fun `sliding along the space bar moves the cursor instead of typing`() {
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        touch(MotionEvent.ACTION_MOVE, x + dp(20f) to y, 20)
        assertEquals(listOf("dragStart"), events)
        touch(MotionEvent.ACTION_MOVE, x + dp(50f) to y, 40)
        touch(MotionEvent.ACTION_MOVE, x + dp(30f) to y, 60)
        touch(MotionEvent.ACTION_UP, x + dp(30f) to y, 80)
        assertEquals(1, movedChars)
        assertEquals("dragEnd", events.last())
        assertTrue("events=$events", events.none { it.startsWith("key:") })
    }

    @Test
    fun `holding the space bar starts moving the cursor without a slide`() {
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        assertEquals(listOf("dragStart"), events)
        touch(MotionEvent.ACTION_UP, x to y, 450)
        assertEquals(listOf("dragStart", "dragEnd"), events)
    }

    @Test
    fun `the default mode ignores vertical travel`() {
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_MOVE, x to y - dp(100f), 450)
        touch(MotionEvent.ACTION_UP, x to y - dp(100f), 500)
        assertEquals(0, movedLines)
        assertEquals(0, movedChars)
    }

    @Test
    fun `the trackpad mode moves in both directions, even off the space bar`() {
        view.spaceDragMode = SpaceDragMode.TRACKPAD
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_MOVE, x - dp(30f) to y - dp(31f), 450)
        touch(MotionEvent.ACTION_MOVE, x - dp(30f) to y - dp(62f), 470)
        touch(MotionEvent.ACTION_UP, x - dp(30f) to y - dp(62f), 490)
        assertEquals(-4, movedChars)
        assertEquals(-2, movedLines)
    }

    @Test
    fun `in the trackpad mode a finger resting at the side keeps the cursor going`() {
        view.spaceDragMode = SpaceDragMode.TRACKPAD
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_MOVE, view.width - 1f to y, 450)
        val atEdge = movedChars
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertTrue("moved=${movedChars - atEdge}", movedChars - atEdge >= 5)

        touch(MotionEvent.ACTION_UP, view.width - 1f to y, 1_000)
        val released = movedChars
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertEquals(released, movedChars)
    }

    @Test
    fun `moving along the bottom of the keyboard stops the cursor being pulled down`() {
        view.spaceDragMode = SpaceDragMode.TRACKPAD
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        val bottom = view.height - 1f
        touch(MotionEvent.ACTION_MOVE, x to y - dp(60f), 500)
        touch(MotionEvent.ACTION_MOVE, x to bottom, 1_000)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertTrue("lines=$movedLines", movedLines > -2)

        touch(MotionEvent.ACTION_MOVE, x - dp(40f) to bottom, 2_000)
        val afterSideways = movedLines
        ShadowLooper.idleMainLooper(1_000, TimeUnit.MILLISECONDS)
        assertEquals(afterSideways, movedLines)
    }

    @Test
    fun `the default mode does not scroll at the side`() {
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_MOVE, view.width - 1f to y, 450)
        val atEdge = movedChars
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertEquals(atEdge, movedChars)
    }

    @Test
    fun `holding the lower part of the space bar in the trackpad mode does not scroll`() {
        view.spaceDragMode = SpaceDragMode.TRACKPAD
        touch(MotionEvent.ACTION_DOWN, center(space).first to view.height - 1f)
        ShadowLooper.idleMainLooper(1_000, TimeUnit.MILLISECONDS)
        assertEquals(0, movedLines)
    }

    @Test
    fun `the keyboard stays quiet for other keys while the cursor moves`() {
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        val (ax, ay) = center("a")
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply { id = 0 },
                MotionEvent.PointerProperties().apply {
                    id =
                        1
                },
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                },
                MotionEvent.PointerCoords().apply {
                    this.x = ax
                    this.y = ay
                },
            )
        val down =
            MotionEvent.obtain(
                downTime,
                downTime + 450,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                0,
                0,
            )
        view.onTouchEvent(down)
        down.recycle()
        assertEquals(listOf("dragStart"), events)
    }

    @Test
    fun `a cancelled touch ends the move`() {
        val (x, y) = center(space)
        touch(MotionEvent.ACTION_DOWN, x to y)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_CANCEL, x to y, 450)
        assertEquals(listOf("dragStart", "dragEnd"), events)
    }

    @Test
    fun `a second finger releases the first key immediately`() {
        val (ax, ay) = center("a")
        val (lx, ly) = center("l")
        touch(MotionEvent.ACTION_DOWN, ax to ay)
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply { id = 0 },
                MotionEvent.PointerProperties().apply {
                    id =
                        1
                },
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = ax
                    y = ay
                },
                MotionEvent.PointerCoords().apply {
                    x = lx
                    y = ly
                },
            )
        val pointerDown =
            MotionEvent.obtain(
                downTime,
                downTime + 30,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                0,
                0,
            )
        view.onTouchEvent(pointerDown)
        pointerDown.recycle()
        assertEquals(listOf("key:CHARACTER:a"), events)
    }
}
