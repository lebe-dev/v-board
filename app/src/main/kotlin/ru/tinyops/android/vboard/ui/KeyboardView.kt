package ru.tinyops.android.vboard.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import ru.tinyops.android.vboard.EnterAction
import ru.tinyops.android.vboard.PerfTrace
import ru.tinyops.android.vboard.core.input.CursorDrag
import ru.tinyops.android.vboard.core.input.CursorEdgeScroll
import ru.tinyops.android.vboard.core.input.DeleteHoldMode
import ru.tinyops.android.vboard.core.input.ShiftState
import ru.tinyops.android.vboard.core.input.SpaceDragMode
import ru.tinyops.android.vboard.core.layout.GeometryMetrics
import ru.tinyops.android.vboard.core.layout.KeySpec
import ru.tinyops.android.vboard.core.layout.KeyType
import ru.tinyops.android.vboard.core.layout.KeyboardGeometry
import ru.tinyops.android.vboard.core.layout.KeyboardSpec
import ru.tinyops.android.vboard.core.layout.PlacedKey
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The key area: turns touches into key presses, long-press alternates and gestures.
 *
 * Touch model:
 *  - a key fires on release; backspace fires on press and auto-repeats while held;
 *  - pressing a second key releases the first one immediately (fast two-thumb typing);
 *  - a finger that starts on a letter and travels further than half a key becomes a
 *    gesture: its points are reported in view coordinates, matching [geometry];
 *  - a finger that holds or slides along the space bar moves the cursor as [spaceDragMode] says.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: KeyboardTheme,
) : View(context) {
    /** A finger on the space bar moving the cursor. */
    interface SpaceDragListener {
        fun onSpaceDragStart()

        /** The finger asks to move the cursor by [chars] characters and [lines] lines. */
        fun onSpaceDragMove(
            chars: Int,
            lines: Int,
        )

        fun onSpaceDragEnd()
    }

    interface Listener : SpaceDragListener {
        fun onKey(key: KeySpec)

        /** The delete key deletes by [step]: a press always deletes a character, holding it goes on as set. */
        fun onDelete(step: DeleteHoldMode)

        fun onAlternate(text: String)

        /** The emoji entry of a long-press popup was chosen. */
        fun onEmojiRequested()

        fun onShiftLongPress()

        fun onLanguageLongPress()

        fun onGestureStart()

        /** Called for every touch event: [points] is the live list, valid only during the call. */
        fun onGestureUpdate(points: List<TouchPoint>)

        fun onGestureEnd(points: List<TouchPoint>)

        fun onGeometryChanged(geometry: KeyboardGeometry)
    }

    var listener: Listener? = null

    var spec: KeyboardSpec? = null
        set(value) {
            if (field == value) return
            val heightChanges = field?.rows?.size != value?.rows?.size
            field = value
            value?.let { renderer.locale = it.language.locale }
            cancelTouches()
            rebuildGeometry()
            if (heightChanges) requestLayout()
        }

    var shiftState: ShiftState
        get() = renderer.shiftState
        set(value) {
            if (renderer.shiftState == value) return
            renderer.shiftState = value
            invalidate()
        }

    var enterAction: EnterAction
        get() = renderer.enterAction
        set(value) {
            if (renderer.enterAction == value) return
            renderer.enterAction = value
            invalidate()
        }

    /** Whether the emoji picker can be opened: its icon and its popup entry are hidden otherwise. */
    var emojiEnabled: Boolean
        get() = renderer.emojiEnabled
        set(value) {
            if (renderer.emojiEnabled == value) return
            renderer.emojiEnabled = value
            invalidate()
        }

    /** Whether a finger sliding across letters is decoded as a gesture. */
    var gestureEnabled: Boolean = false

    /** What holding the delete key does after the first character. */
    var deleteHold: DeleteHoldMode = DeleteHoldMode.CHARACTERS

    /** How a finger on the space bar moves the cursor. */
    var spaceDragMode: SpaceDragMode = SpaceDragMode.LINEAR

    var geometry: KeyboardGeometry? = null
        private set

    private val renderer = KeyRenderer(theme)
    private val handler = Handler(Looper.getMainLooper())

    private class Pointer(
        val id: Int,
        val key: PlacedKey,
        val downX: Float,
        val downY: Float,
        var lastTime: Long,
    ) {
        var longPressed = false
        var lastX = downX
        var lastY = downY
    }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private var popup: AlternatesPopup? = null
    private var longPress: Runnable? = null
    private var repeat: Runnable? = null

    private var gesturePointerId = NO_POINTER
    private var gestureStartTime = 0L
    private var gestureActive = false
    private val gesturePoints = ArrayList<TouchPoint>()
    private var trailAlpha = 1f
    private var trailFade: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val linearDrag = CursorDrag(CHAR_STEP_DP * density, LINE_STEP_DP * density)
    private val trackpadDrag =
        CursorDrag(
            TRACKPAD_CHAR_STEP_DP * density,
            TRACKPAD_LINE_STEP_DP * density,
            SLOW_SPEED_DP_PER_MS * density,
            FAST_SPEED_DP_PER_MS * density,
            TRACKPAD_MAX_GAIN,
        )
    private val edgeScroll = CursorEdgeScroll(EDGE_MARGIN_X_DP * density, EDGE_MARGIN_Y_DP * density)
    private val edgeTick = Runnable { onEdgeTick() }
    private var edgeTicking = false
    private var spaceDragPointerId = NO_POINTER
    private val trackpadActive get() = spaceDragPointerId != NO_POINTER && spaceDragMode == SpaceDragMode.TRACKPAD

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val rows = spec?.rows?.size ?: DEFAULT_ROWS
        val height = theme.paddingTop + theme.paddingBottom + rows * theme.rowHeight
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height.toInt())
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        val spec = spec
        geometry = null
        if (spec != null && width > 0 && height > 0) {
            val metrics =
                GeometryMetrics(
                    horizontalGap = theme.keyGapHorizontal,
                    verticalGap = theme.keyGapVertical,
                    horizontalPadding = theme.paddingHorizontal,
                    topPadding = theme.paddingTop,
                    bottomPadding = theme.paddingBottom,
                )
            val geometry = KeyboardGeometry(spec, width.toFloat(), height.toFloat(), metrics)
            this.geometry = geometry
            renderer.setKeyHeight(
                geometry.keys
                    .firstOrNull()
                    ?.bounds
                    ?.height ?: theme.rowHeight,
            )
            listener?.onGeometryChanged(geometry)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) = PerfTrace.section(PerfTrace.DRAW) { drawKeyboard(canvas) }

    private fun drawKeyboard(canvas: Canvas) {
        val geometry = geometry ?: return
        if (trackpadActive) {
            for (key in geometry.keys) renderer.drawKeySkeleton(canvas, key)
            return
        }
        // A new finger releases the previous one, so at most one key is pressed.
        val pressed = pointers.values.lastOrNull()?.key
        for (key in geometry.keys) renderer.drawKey(canvas, key, key === pressed)
        if (gesturePoints.size > 1 && (gestureActive || trailFade != null)) {
            renderer.drawTrail(canvas, gesturePoints, trailAlpha)
        }
        val popup = popup
        if (popup != null) {
            renderer.drawPopup(canvas, popup)
        } else if (!gestureActive) {
            pressed?.let { renderer.drawPreview(canvas, it) }
        }
    }

    // region Touch handling

    @SuppressLint("ClickableViewAccessibility") // performClick() is called for every completed tap.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                onPointerDown(event.getPointerId(index), event.getX(index), event.getY(index), event.eventTime)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) onPointerMove(event, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                onPointerUp(event.getPointerId(index), event.getX(index), event.getY(index), event.eventTime)
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onPointerDown(
        id: Int,
        x: Float,
        y: Float,
        time: Long,
    ) {
        if (gestureActive || popup != null || spaceDragPointerId != NO_POINTER) return
        // Rolling input: a new finger releases the keys still held.
        pointers.values.toList().forEach { release(it) }
        pointers.clear()

        val key = geometry?.keyAt(x, y) ?: return
        val pointer = Pointer(id, key, x, y, time)
        pointers[id] = pointer
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (key.spec.type == KeyType.BACKSPACE) {
            listener?.onDelete(DeleteHoldMode.CHARACTERS)
            scheduleDeleteHold()
        } else if (hasLongPress(key.spec)) {
            scheduleLongPress(pointer)
        }

        gesturePointerId = NO_POINTER
        if (gestureEnabled && key.spec.isLetter) {
            gesturePointerId = id
            gestureStartTime = time
            stopTrailFade()
            gesturePoints.clear()
            gesturePoints += TouchPoint(TouchAction.DOWN, id, x, y, 0.0)
        }
        invalidate()
    }

    private fun onPointerMove(
        event: MotionEvent,
        index: Int,
    ) {
        val id = event.getPointerId(index)
        val popup = popup
        if (popup != null && popup.pointerId == id) {
            updatePopupSelection(popup, event.getX(index), event.getY(index))
        } else if (pointers[id]?.key?.spec?.type == KeyType.SPACE) {
            onSpaceMove(requireNotNull(pointers[id]), event.getX(index), event.getY(index), event.eventTime)
        } else if (id == gesturePointerId) {
            for (h in 0 until event.historySize) {
                val time = event.getHistoricalEventTime(h)
                addGesturePoint(id, event.getHistoricalX(index, h), event.getHistoricalY(index, h), time)
            }
            addGesturePoint(id, event.getX(index), event.getY(index), event.eventTime)
            if (gestureActive) {
                listener?.onGestureUpdate(gesturePoints)
                invalidate()
            } else {
                maybeStartGesture(id, event.getX(index), event.getY(index))
            }
        }
    }

    private fun onSpaceMove(
        pointer: Pointer,
        x: Float,
        y: Float,
        time: Long,
    ) {
        pointer.lastX = x
        pointer.lastY = y
        pointer.lastTime = time
        if (pointer.id != spaceDragPointerId) {
            // Far enough to be moving the cursor rather than typing a space.
            val dx = x - pointer.downX
            val travel = if (spaceDragMode == SpaceDragMode.TRACKPAD) hypot(dx, y - pointer.downY) else abs(dx)
            if (travel >= SPACE_SLIDE_DP * density) startSpaceDrag(pointer)
            return
        }
        val trackpad = spaceDragMode == SpaceDragMode.TRACKPAD
        val drag = if (trackpad) trackpadDrag else linearDrag
        drag.moveTo(x, y, time)
        val chars = drag.takeChars()
        val lines = if (trackpad) drag.takeLines() else 0
        if (trackpad) {
            edgeScroll.moveTo(x, y)
            updateEdgeTick()
        }
        if (chars != 0 || lines != 0) {
            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            listener?.onSpaceDragMove(chars, lines)
        }
    }

    private fun startSpaceDrag(pointer: Pointer) {
        cancelLongPressTimer()
        pointer.longPressed = true
        spaceDragPointerId = pointer.id
        (if (spaceDragMode == SpaceDragMode.TRACKPAD) trackpadDrag else linearDrag)
            .start(pointer.lastX, pointer.lastY, pointer.lastTime)
        if (trackpadActive) {
            edgeScroll.start(pointer.lastX, pointer.lastY, width.toFloat(), height.toFloat())
            updateEdgeTick()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onSpaceDragStart()
        invalidate()
    }

    private fun endSpaceDrag() {
        if (spaceDragPointerId == NO_POINTER) return
        spaceDragPointerId = NO_POINTER
        handler.removeCallbacks(edgeTick)
        edgeTicking = false
        listener?.onSpaceDragEnd()
        invalidate()
    }

    /** The clock of [edgeScroll] runs only while the finger is in a zone. */
    private fun updateEdgeTick() {
        if (edgeTicking || !edgeScroll.active) return
        edgeTicking = true
        handler.postDelayed(edgeTick, EDGE_TICK_MILLIS)
    }

    private fun onEdgeTick() {
        edgeTicking = false
        if (!trackpadActive) return
        edgeScroll.advance(EDGE_TICK_MILLIS)
        val chars = edgeScroll.takeChars()
        val lines = edgeScroll.takeLines()
        if (chars != 0 || lines != 0) listener?.onSpaceDragMove(chars, lines)
        updateEdgeTick()
    }

    private fun maybeStartGesture(
        id: Int,
        x: Float,
        y: Float,
    ) {
        val pointer = pointers[id] ?: return
        val threshold = pointer.key.bounds.width * GESTURE_THRESHOLD
        if (hypot(x - pointer.downX, y - pointer.downY) < threshold) return
        cancelLongPressTimer()
        pointers.remove(id)
        gestureActive = true
        listener?.onGestureStart()
        invalidate()
    }

    private fun addGesturePoint(
        id: Int,
        x: Float,
        y: Float,
        time: Long,
    ) {
        gesturePoints += TouchPoint(TouchAction.MOVE, id, x, y, (time - gestureStartTime).toDouble())
    }

    private fun onPointerUp(
        id: Int,
        x: Float,
        y: Float,
        time: Long,
    ) {
        val popup = popup
        if (id == spaceDragPointerId) endSpaceDrag()
        when {
            gestureActive && id == gesturePointerId -> {
                gesturePoints += TouchPoint(TouchAction.UP, id, x, y, (time - gestureStartTime).toDouble())
                gestureActive = false
                gesturePointerId = NO_POINTER
                listener?.onGestureEnd(ArrayList(gesturePoints))
                startTrailFade()
            }
            popup != null && popup.pointerId == id -> {
                this.popup = null
                pointers.remove(id)
                val chosen = popup.alternates[popup.selected]
                if (chosen == KeySpec.EMOJI_ALTERNATE) listener?.onEmojiRequested() else listener?.onAlternate(chosen)
                performClick()
            }
            else -> pointers.remove(id)?.let { release(it) }
        }
        if (id == gesturePointerId) gesturePointerId = NO_POINTER
        invalidate()
    }

    /** Ends a key press: fires the key unless a long press consumed it. */
    private fun release(pointer: Pointer) {
        cancelLongPressTimer()
        if (pointer.id == gesturePointerId) gesturePointerId = NO_POINTER
        if (pointer.key.spec.type == KeyType.BACKSPACE) {
            cancelRepeat()
        } else if (!pointer.longPressed) {
            listener?.onKey(pointer.key.spec)
            performClick()
        }
    }

    private fun cancelTouches() {
        endSpaceDrag()
        cancelLongPressTimer()
        cancelRepeat()
        pointers.clear()
        popup = null
        gestureActive = false
        gesturePointerId = NO_POINTER
        gesturePoints.clear()
        stopTrailFade()
        invalidate()
    }

    private fun hasLongPress(spec: KeySpec): Boolean =
        spec.alternates.isNotEmpty() ||
            spec.type == KeyType.SHIFT ||
            spec.type == KeyType.LANGUAGE ||
            spec.type == KeyType.SPACE

    private fun scheduleLongPress(pointer: Pointer) {
        cancelLongPressTimer()
        val runnable = Runnable { onLongPress(pointer) }
        longPress = runnable
        handler.postDelayed(runnable, LONG_PRESS_MILLIS)
    }

    private fun onLongPress(pointer: Pointer) {
        longPress = null
        if (pointers[pointer.id] !== pointer) return
        if (pointer.key.spec.type == KeyType.SPACE) {
            startSpaceDrag(pointer)
            return
        }
        pointer.longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        when (pointer.key.spec.type) {
            KeyType.SHIFT -> listener?.onShiftLongPress()
            KeyType.LANGUAGE -> listener?.onLanguageLongPress()
            else -> showPopup(pointer)
        }
        invalidate()
    }

    private fun showPopup(pointer: Pointer) {
        val alternates =
            pointer.key.spec.alternates
                .filter { emojiEnabled || it != KeySpec.EMOJI_ALTERNATE }
        if (alternates.isEmpty()) return
        if (gesturePointerId == pointer.id) gesturePointerId = NO_POINTER
        val maxX = width - theme.paddingHorizontal
        val grid = PopupGrid.of(alternates.size)
        val bounds = renderer.popupBounds(pointer.key, grid, theme.paddingHorizontal, maxX)
        val cellHeight = bounds.height() / grid.rows
        popup = AlternatesPopup(pointer.id, alternates, bounds, grid, pointer.key.bounds.width, cellHeight)
    }

    private fun updatePopupSelection(
        popup: AlternatesPopup,
        x: Float,
        y: Float,
    ) {
        val index = popup.indexAt(x, y)
        if (index != popup.selected) {
            popup.selected = index
            invalidate()
        }
    }

    private fun cancelLongPressTimer() {
        longPress?.let { handler.removeCallbacks(it) }
        longPress = null
    }

    private fun scheduleDeleteHold() {
        cancelRepeat()
        val mode = deleteHold
        if (mode == DeleteHoldMode.NONE) return
        val runnable =
            object : Runnable {
                override fun run() {
                    listener?.onDelete(mode)
                    when (mode) {
                        DeleteHoldMode.ALL -> {
                            repeat = null
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        DeleteHoldMode.WORDS -> handler.postDelayed(this, WORD_REPEAT_INTERVAL_MILLIS)
                        else -> handler.postDelayed(this, REPEAT_INTERVAL_MILLIS)
                    }
                }
            }
        repeat = runnable
        handler.postDelayed(runnable, if (mode == DeleteHoldMode.ALL) CLEAR_HOLD_MILLIS else REPEAT_START_MILLIS)
    }

    private fun cancelRepeat() {
        repeat?.let { handler.removeCallbacks(it) }
        repeat = null
    }

    private fun startTrailFade() {
        stopTrailFade()
        trailFade =
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = TRAIL_FADE_MILLIS
                addUpdateListener {
                    trailAlpha = it.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (trailFade === animation) trailFade = null
                            gesturePoints.clear()
                        }
                    },
                )
                start()
            }
    }

    private fun stopTrailFade() {
        val fade = trailFade
        trailFade = null
        fade?.cancel()
        trailAlpha = 1f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTouches()
    }

    // endregion

    private companion object {
        const val NO_POINTER = -1
        const val DEFAULT_ROWS = 4
        const val LONG_PRESS_MILLIS = 350L
        const val REPEAT_START_MILLIS = 400L
        const val REPEAT_INTERVAL_MILLIS = 50L
        const val WORD_REPEAT_INTERVAL_MILLIS = 150L

        /** Clearing the field cannot be stopped halfway, so it waits longer than the repeat. */
        const val CLEAR_HOLD_MILLIS = 800L
        const val TRAIL_FADE_MILLIS = 250L

        /** Travel, as a fraction of the key width, that turns a press into a gesture. */
        const val GESTURE_THRESHOLD = 0.5f

        /** Travel along the space bar that turns a press into moving the cursor. */
        const val SPACE_SLIDE_DP = 16f

        /** Finger travel per character and per line of cursor movement. */
        const val CHAR_STEP_DP = 10f
        const val LINE_STEP_DP = 30f

        /** The trackpad mode follows the finger about one to one, as characters and lines are about this big. */
        const val TRACKPAD_CHAR_STEP_DP = 7f
        const val TRACKPAD_LINE_STEP_DP = 24f

        /** A sharp move goes up to this many times further than a slow one. */
        const val TRACKPAD_MAX_GAIN = 6f
        const val SLOW_SPEED_DP_PER_MS = 0.15f
        const val FAST_SPEED_DP_PER_MS = 1.5f

        /** How far from the edges of the keyboard a resting finger keeps the cursor going. */
        const val EDGE_MARGIN_X_DP = 48f
        const val EDGE_MARGIN_Y_DP = 32f
        const val EDGE_TICK_MILLIS = 16L
    }
}
