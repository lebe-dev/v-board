package ru.tinyops.android.vboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.clipboard.ClipChip
import ru.tinyops.android.vboard.core.layout.KeyStyle
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonPlaces
import ru.tinyops.android.vboard.voice.ProofreadState
import ru.tinyops.android.vboard.voice.VoiceState

/**
 * The strip above the keys: settings (cog) on the left, voice input (microphone in a round button)
 * on the right, and the clipboard history in the place the user chose. Between them, three suggestion
 * slots while typing (the cog and the clipboard button are hidden then and the slots span their room):
 * the first suggestion is the primary one and is shown in the middle slot in bold, the second on
 * the left and the third on the right. With nothing to suggest, the recently copied text, image
 * and the links found in it are shown as chips, centered, that paste on tap. Left of the microphone
 * sits the proofread button, while proofreading is on and the field has text. The custom buttons
 * and the clipboard button stand on the left, in the center or on the right of the room between the
 * buttons while nothing is suggested; the chips get the widest stretch they leave. The fullscreen button,
 * when the user turned it on, stands with them and gives way in the same way. These buttons may be made
 * narrower than the strip is high, so that more of them fit. With the second row turned on, the custom
 * buttons that do not fit go to a row under the strip, which stays while typing so that the keyboard
 * keeps its height.
 *
 * While dictating, the strip turns into the voice panel: × cancels, the middle shows the status
 * or the text waiting to be inserted, and the highlighted microphone ends the dictation.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(
    context: Context,
    private val theme: KeyboardTheme,
) : View(context) {
    interface Listener {
        fun onSuggestionPicked(word: String)

        fun onSettingsClicked()

        fun onClipboardClicked()

        fun onFullscreenClicked()

        fun onChipClicked(chip: ClipChip)

        fun onMicClicked()

        fun onVoiceCancel()

        fun onProofreadClicked()

        /** The proofread button was held for a while. */
        fun onProofreadHeld()

        /** A custom button was tapped, or held for a while when [held]. */
        fun onCustomButtonUsed(
            id: String,
            held: Boolean,
        )
    }

    /** Touch targets of the strip. */
    enum class Zone {
        LEFT_BUTTON,
        SLOT_LEFT,
        SLOT_MIDDLE,
        SLOT_RIGHT,
        CHIP_0,
        CHIP_1,
        CHIP_2,
        CUSTOM,
        CLIPBOARD,
        FULLSCREEN,
        PROOFREAD,
        MIC,
        NONE,
    }

    var listener: Listener? = null

    /** The undo arrow of a custom button held for a while: a preview of the undo shows until the finger lets go. */
    internal val undoPeek = UndoPeek(UndoPreviewPopup(context, theme), this)

    /** False in fields where dictation makes no sense (passwords): the microphone is hidden. */
    var voiceAvailable: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Whether holding the proofread button does something of its own. */
    var proofreadHolds = false

    /** What the proofread button does on a tap; null hides it. */
    var proofreadState: ProofreadState? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Every custom button of the field in the order they stand, also those the strip hides for now. */
    private var offered: List<StripButton> = emptyList()

    /**
     * The custom buttons in the order they are shown; as many as fit are drawn. Setting them takes every button:
     * the ones that fit on the strip go away while they are [StripButton.hidden], the rest stand in the second
     * row all the same.
     */
    var customButtons: List<StripButton>
        get() = arranged.buttons
        set(value) {
            if (offered == value) return
            offered = value
            relayoutButtons()
        }

    /**
     * How many custom buttons the field may show, with text in it or without: the second row is kept for
     * those that would not fit, so the keyboard does not change its height when the first letter is typed.
     */
    var plannedButtons: Int
        get() = rows.planned
        set(value) {
            rows.planned = value
            relayoutButtons()
        }

    /** Whether the custom buttons that do not fit on the strip go to a second row under it. */
    var secondRow: Boolean
        get() = rows.secondRow
        set(value) {
            rows.secondRow = value
            relayoutButtons()
        }

    /** The width of a custom, clipboard or fullscreen button as a share of the strip's height. */
    var buttonWidthScale: Float
        get() = rows.widthScale
        set(value) {
            rows.widthScale = value
            relayoutButtons()
        }

    /** The color of the proofread icon; null keeps it like the other icons. */
    var proofreadColor: ButtonColor? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Whether dictated text is improved by a server; the microphone then carries a sparkle. */
    var micEnhanced: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The color of the microphone icon; null keeps it like the other icons. */
    var micColor: ButtonColor? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The color of the clipboard button's icon; null keeps it like the other icons. */
    var clipboardColor: ButtonColor? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Which part of the room between the buttons the clipboard button stands in; null hides it. */
    var clipboardPlacement: ButtonPlacement? = ButtonPlacement.RIGHT
        set(value) {
            if (field == value) return
            field = value
            relayoutButtons()
        }

    /** Which part of the room between the buttons the fullscreen button stands in; null hides it. */
    var fullscreenPlacement: ButtonPlacement? = null
        set(value) {
            if (field == value) return
            field = value
            relayoutButtons()
        }

    /** The color of the fullscreen button's icon; null keeps it like the other icons. */
    var fullscreenColor: ButtonColor? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var voiceState: VoiceState = VoiceState()
        set(value) {
            if (field == value) return
            // The status changes all the time while dictating; only the panel coming or going moves the buttons.
            if (field.active != value.active) setPressed(Hit.NONE)
            field = value
            invalidate()
        }

    private var suggestions: List<String> = emptyList()

    /**
     * A touch target, with the id of the custom button for [Zone.CUSTOM]: the buttons may be published again
     * while the finger is down, and the button keeps its tap as long as it stays where the finger came down.
     */
    private data class Hit(
        val zone: Zone,
        val id: String? = null,
    ) {
        companion object {
            val NONE = Hit(Zone.NONE)
        }
    }

    private var pressed = Hit.NONE

    private val tap = TapSlop(context)

    private val clipChips = StripChips(context, theme)

    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyColor(KeyStyle.LETTER, false) }
    private val micPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyColor(KeyStyle.LETTER, true) }

    private val textPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = theme.suggestionTextSize
            color = theme.suggestion
        }
    private val boldPaint = TextPaint(textPaint).apply { typeface = Typeface.DEFAULT_BOLD }
    private val statusPaint = TextPaint(textPaint).apply { color = theme.voiceStatus }
    private val dividerPaint = Paint().apply { color = theme.suggestionDivider }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.suggestionPressed }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.voiceActive }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.voiceHalo }
    private val spinner = StripSpinner(theme.stripIconSize)

    /** Light icons mean a dark keyboard, whichever theme it comes from. */
    private val darkTheme = ColorUtils.calculateLuminance(theme.stripIcon) > HALF_LUMINANCE

    private val groupPopup =
        ButtonGroupPopup(context, theme, undoPeek.preview) { id, held -> listener?.onCustomButtonUsed(id, held) }

    private val holdAction =
        Runnable {
            val zone = pressed.zone
            val button = pressed.id?.let { id -> customButtons.firstOrNull { it.id == id } }
            if (zone != Zone.PROOFREAD && button?.holds != true) return@Runnable
            setPressed(Hit.NONE)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            when {
                zone == Zone.PROOFREAD && proofreadState == ProofreadState.UNDO -> undoPeek.begin(UndoPeek.PROOFREAD)
                zone == Zone.PROOFREAD -> listener?.onProofreadHeld()
                button == null -> Unit
                button.state == ProofreadState.UNDO -> undoPeek.begin(button.id)
                else -> listener?.onCustomButtonUsed(button.id, held = true)
            }
        }

    /** How long suggestions stay after the last update; 0 keeps them. */
    var suggestionTimeoutMillis: Long = 0L

    /** A finger on a suggestion is about to pick it: the words wait for it. */
    private val hideSuggestions: Runnable =
        Runnable {
            if (pressed == Hit.NONE) clear() else postDelayed(hideSuggestions, suggestionTimeoutMillis)
        }

    fun setSuggestions(words: List<String>) {
        val next = words.take(SLOTS)
        removeCallbacks(hideSuggestions)
        if (next.isNotEmpty() && suggestionTimeoutMillis > 0L) postDelayed(hideSuggestions, suggestionTimeoutMillis)
        if (next == suggestions) return
        suggestions = next
        // A finger on a slot would pick a word it did not see.
        if (pressed.zone in SLOT_ZONES) setPressed(Hit.NONE)
        invalidate()
    }

    fun clear() = setSuggestions(emptyList())

    /** The chips of the recent clip; [image] is the thumbnail of an image chip, when it is loaded. */
    fun setClipChips(
        next: List<ClipChip>,
        image: Bitmap? = null,
    ) {
        if (!clipChips.set(next, image)) return
        if (pressed.zone in CHIP_ZONES) setPressed(Hit.NONE)
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        measuredRows = rows.rows(width.toFloat(), offered.size, fixedCount, offered.any { it.secondRow })
        setMeasuredDimension(width, rowHeight * measuredRows)
    }

    // region Geometry

    /** The height of a row; the strip is one or two of them. */
    private val rowHeight: Int = theme.suggestionHeight.toInt()

    private val rows = StripButtonRows(rowHeight, theme.stripIconSize * MIN_ICON_ROOM)

    private var measuredRows = 1

    /** The cog, the microphone and the proofread button are as wide as the strip is high. */
    private val buttonWidth: Float get() = rowHeight.toFloat()

    /** The custom, clipboard and fullscreen buttons. */
    private val slotSize: Float get() = rows.size

    private val fixedCount: Int get() = clipboardCount + if (fullscreenPlacement != null) 1 else 0

    /**
     * The buttons moved: the strip grows by a row or shrinks back as soon as they ask for it. A finger on a button
     * keeps it, and the lift-off checks that the button is still under the place it came down.
     */
    private fun relayoutButtons() {
        if (width > 0 &&
            rows.rows(width.toFloat(), offered.size, fixedCount, offered.any { it.secondRow }) != measuredRows
        ) {
            requestLayout()
        }
        invalidate()
    }

    /** While suggestions are shown the cog and the clipboard button give their room to the slots. */
    private val typing: Boolean get() = suggestions.isNotEmpty() && !voiceState.active

    private val slotsLeft: Float get() = if (typing) 0f else buttonWidth

    @get:DrawableRes
    private val micIcon: Int get() = if (micEnhanced) R.drawable.ic_mic_spark else R.drawable.ic_mic

    private val micVisible: Boolean get() = voiceAvailable || voiceState.active

    private val micLeft: Float get() = width - buttonWidth

    /**
     * The proofread button gives its room to the suggestions, unless it shows a running request or an undo, which
     * the user must not lose sight of.
     */
    private val proofreadVisible: Boolean
        get() {
            val state = proofreadState ?: return false
            if (voiceState.active) return false
            return !typing || state != ProofreadState.IDLE
        }

    /** The proofread button sits left of the microphone, or at the right edge when the microphone is hidden. */
    private val proofreadRight: Float get() = if (micVisible) micLeft else width.toFloat()

    /** The proofread button, when shown, takes the last button's room before the microphone. */
    private val proofreadLeft: Float
        get() = if (proofreadVisible) proofreadRight - buttonWidth else proofreadRight

    /** The custom, clipboard and fullscreen buttons give way to suggestions and to dictation. */
    private val buttonsVisible: Boolean get() = !voiceState.active && !typing

    private val clipboardCount: Int get() = if (clipboardPlacement != null) 1 else 0

    /**
     * The right end of the room that decides how many buttons stand on the strip. With the second row on, the
     * proofread button keeps its place all the time, so the button that moved down does not jump when text
     * appears and the proofread button comes.
     */
    private val capacityRight: Float
        get() = if (rows.secondRow && !proofreadVisible) proofreadRight - buttonWidth else slotsRight

    /** The right end of the area between the buttons. */
    private val slotsRight: Float get() = proofreadLeft

    private val slotWidth: Float get() = (slotsRight - slotsLeft) / SLOTS

    private val unit: Float get() = theme.stripIconSize / UNITS_PER_ICON

    /**
     * How many custom buttons the strip has room for while nothing is suggested; the clipboard and fullscreen
     * buttons keep their places first. The second row starts after them also while typing.
     */
    internal val firstRowCount: Int get() = arranged.firstRow

    private val arranged: StripButtonRows.Arrangement
        get() = rows.arrange(offered, rows.fit(buttonWidth, capacityRight, offered.size, fixedCount))

    /** How many custom buttons are shown on the strip. */
    private val customsShown: Int get() = if (buttonsVisible) firstRowCount else 0

    /** The custom buttons of the second row, across the whole width; it gives way to dictation only. */
    private val secondPlaces: ButtonPlaces
        get() {
            val active = measuredRows == 2 && !voiceState.active
            val buttons = if (active) customButtons.drop(firstRowCount) else emptyList()
            return rows.below(buttons.map { it.placement }, width.toFloat())
        }

    /**
     * Nothing stands while typing or dictating. The clipboard button comes after the custom buttons, so on the
     * right it stays next to the proofread button, unless the fullscreen button, which comes last, stands there too.
     */
    private val places: ButtonPlaces
        get() {
            if (!buttonsVisible) return rows.onStrip(emptyList(), slotsLeft, slotsRight)
            val fixed = listOfNotNull(clipboardPlacement, fullscreenPlacement)
            return rows.onStrip(customButtons.take(customsShown).map { it.placement } + fixed, slotsLeft, slotsRight)
        }

    /**
     * The left edge and the bottom of the custom button at [index] of the list, or null while it is not drawn: the
     * strip leaves its buttons out while typing, and the second row stays.
     */
    private fun boxOf(index: Int): Pair<Float, Int>? =
        when {
            index >= firstRowCount -> secondPlaces.lefts.getOrNull(index - firstRowCount)?.let { it to rowHeight * 2 }
            index in 0 until customsShown -> places.lefts.getOrNull(index)?.let { it to rowHeight }
            else -> null
        }

    /** The left edge of the clipboard button, or null where it is not shown. */
    private val clipboardLeft: Float?
        get() = if (buttonsVisible && clipboardPlacement != null) places.lefts.getOrNull(customsShown) else null

    /** The left edge of the fullscreen button, or null where it is not shown. */
    private val fullscreenLeft: Float?
        get() {
            if (!buttonsVisible || fullscreenPlacement == null) return null
            return places.lefts.getOrNull(customsShown + clipboardCount)
        }

    /** Chips are transient and a custom button is always wanted, so the buttons keep their place. */
    private val showsChips: Boolean
        get() {
            if (clipChips.chips.isEmpty() || suggestions.isNotEmpty() || voiceState.active) return false
            val free = places
            return free.gapEnd - free.gapStart >= rowHeight * MIN_CHIP_ROOM
        }

    /** The zone under [x] and [y]; slots, chips and the microphone only where they have something to act on. */
    fun zoneAt(
        x: Float,
        y: Float = rowHeight / 2f,
    ): Zone = hitAt(x, y).zone

    /** The index of the custom button under [x] and [y], or -1. */
    internal fun customAt(
        x: Float,
        y: Float = rowHeight / 2f,
    ): Int {
        if (y < rowHeight) return places.indexAt(x).takeIf { it in 0 until customsShown } ?: -1
        return secondPlaces.indexAt(x).takeIf { it >= 0 }?.plus(firstRowCount) ?: -1
    }

    private fun hitAt(
        x: Float,
        y: Float,
    ): Hit {
        val custom = customAt(x, y)
        if (custom >= 0) return Hit(Zone.CUSTOM, customButtons[custom].id)
        if (y >= rowHeight) return Hit.NONE
        return Hit(zoneOnStrip(x, places.indexAt(x)))
    }

    /** What else stands on the strip under [x]; [custom] is the place of a button there, or -1. */
    private fun zoneOnStrip(
        x: Float,
        custom: Int,
    ): Zone =
        when {
            x < slotsLeft -> Zone.LEFT_BUTTON
            x >= proofreadRight -> if (micVisible) Zone.MIC else Zone.NONE
            x >= proofreadLeft -> Zone.PROOFREAD
            voiceState.active -> Zone.NONE
            custom >= customsShown && buttonsVisible ->
                if (custom - customsShown < clipboardCount) Zone.CLIPBOARD else Zone.FULLSCREEN
            showsChips -> chipZoneAt(x)
            else ->
                when (((x - slotsLeft) / slotWidth).toInt().coerceIn(0, SLOTS - 1)) {
                    0 -> Zone.SLOT_LEFT
                    1 -> Zone.SLOT_MIDDLE
                    else -> Zone.SLOT_RIGHT
                }.takeIf { wordIn(it, suggestions) != null } ?: Zone.NONE
        }

    private fun chipZoneAt(x: Float): Zone = CHIP_ZONES.getOrNull(clipChips.indexAt(x, chipRoom)) ?: Zone.NONE

    /** The chips take the widest stretch the buttons leave. */
    private val chipRoom: ChipRoom
        get() {
            val free = places
            return ChipRoom(free.gapStart, free.gapEnd, unit, rowHeight)
        }

    // endregion

    override fun onDraw(canvas: Canvas) {
        if (voiceState.active) drawVoicePanel(canvas) else drawSuggestions(canvas)
    }

    private fun drawSuggestions(canvas: Canvas) {
        drawPressed(canvas)
        if (!typing) {
            drawIcon(canvas, R.drawable.ic_settings, buttonWidth / 2f, theme.stripIcon)
        }
        clipboardLeft?.let {
            val tint = clipboardColor?.tint(darkTheme, theme.accent) ?: theme.stripIcon
            drawIcon(canvas, R.drawable.ic_content_paste, it + slotSize / 2f, tint)
        }
        fullscreenLeft?.let {
            val tint = fullscreenColor?.tint(darkTheme, theme.accent) ?: theme.stripIcon
            drawIcon(canvas, R.drawable.ic_fullscreen, it + slotSize / 2f, tint)
        }
        if (proofreadVisible) {
            val center = proofreadLeft + buttonWidth / 2f
            val tint = proofreadColor?.tint(darkTheme, theme.accent) ?: theme.stripIcon
            drawActionButton(canvas, proofreadState, R.drawable.ic_auto_fix_high, center, tint)
        }
        drawCustomButtons(canvas)
        if (voiceAvailable) drawMicButton(canvas)
        if (showsChips) clipChips.draw(canvas, chipRoom, CHIP_ZONES.indexOf(pressed.zone))
        if (suggestions.isEmpty()) return
        val baseline = textPaint.baselineIn(rowHeight)
        for ((slot, zone) in SLOT_ZONES.withIndex()) {
            val word = wordIn(zone, suggestions) ?: continue
            val paint = if (zone == Zone.SLOT_MIDDLE) boldPaint else textPaint
            val text = TextUtils.ellipsize(word, paint, slotWidth * TEXT_WIDTH, TextUtils.TruncateAt.END)
            val center = slotsLeft + slot * slotWidth + slotWidth / 2f
            canvas.drawText(text, 0, text.length, center, baseline, paint)
        }
        val dividerHeight = rowHeight * DIVIDER_HEIGHT
        for (slot in 1 until SLOTS) {
            val x = slotsLeft + slot * slotWidth
            canvas.drawRect(x, (rowHeight - dividerHeight) / 2f, x + 1f, (rowHeight + dividerHeight) / 2f, dividerPaint)
        }
    }

    /** The custom buttons of the strip, then those of the second row under it. */
    private fun drawCustomButtons(canvas: Canvas) {
        val strip = places.lefts
        val below = secondPlaces.lefts
        val first = firstRowCount
        for (index in 0 until customsShown + below.size) {
            val onStrip = index < customsShown
            val button = customButtons[if (onStrip) index else first + index - customsShown]
            val left = if (onStrip) strip[index] else below[index - customsShown]
            val centerY = if (onStrip) rowHeight / 2f else rowHeight + rowHeight / 2f
            val tint = button.color?.tint(darkTheme, theme.accent) ?: theme.stripIcon
            drawActionButton(canvas, button.state, button.icon, left + slotSize / 2f, tint, centerY)
        }
    }

    /** The microphone sits on a round button. */
    private fun drawMicButton(canvas: Canvas) {
        val centerX = micLeft + buttonWidth / 2f
        val paint = if (pressed.zone == Zone.MIC) micPressedPaint else micPaint
        canvas.drawCircle(centerX, rowHeight / 2f, rowHeight * MIC_RADIUS, paint)
        drawIcon(canvas, micIcon, centerX, micColor?.tint(darkTheme, theme.accent) ?: theme.stripIcon)
    }

    /** A button that sends text to a server: its icon, a spinner during the request or the undo arrow after it. */
    private fun drawActionButton(
        canvas: Canvas,
        state: ProofreadState?,
        @DrawableRes icon: Int,
        centerX: Float,
        tint: Int,
        centerY: Float = rowHeight / 2f,
    ) {
        when (state) {
            ProofreadState.WORKING -> {
                spinner.draw(canvas, centerX, centerY, tint)
                postInvalidateOnAnimation()
            }
            ProofreadState.UNDO -> drawIcon(canvas, R.drawable.ic_undo, centerX, tint, centerY)
            else -> drawIcon(canvas, icon, centerX, tint, centerY)
        }
    }

    private fun drawVoicePanel(canvas: Canvas) {
        drawPressed(canvas)
        drawIcon(canvas, R.drawable.ic_close, buttonWidth / 2f, theme.stripIcon)
        val micCenter = micLeft + buttonWidth / 2f
        val radius = rowHeight * ACTIVE_RADIUS
        if (voiceState.speech) canvas.drawCircle(micCenter, rowHeight / 2f, radius * HALO_SCALE, haloPaint)
        canvas.drawCircle(micCenter, rowHeight / 2f, radius, activePaint)
        drawIcon(canvas, micIcon, micCenter, theme.voiceActiveIcon)

        val preview = voiceState.preview
        val (text, paint) =
            if (preview.isNotEmpty() && voiceState.phase == VoiceState.Phase.LISTENING) {
                preview to textPaint
            } else {
                context.getString(voiceState.statusText()) to statusPaint
            }
        val available = (slotsRight - slotsLeft) * TEXT_WIDTH
        // The end of a long preview is what was said last.
        val shown = TextUtils.ellipsize(text, paint, available, TextUtils.TruncateAt.START)
        canvas.drawText(shown, 0, shown.length, (slotsLeft + slotsRight) / 2f, paint.baselineIn(rowHeight), paint)
    }

    private fun drawPressed(canvas: Canvas) {
        var top = 0f
        val (left, right) =
            when (pressed.zone) {
                Zone.NONE -> return
                Zone.LEFT_BUTTON -> 0f to buttonWidth
                Zone.CLIPBOARD -> clipboardLeft?.let { it to it + slotSize } ?: return
                Zone.FULLSCREEN -> fullscreenLeft?.let { it to it + slotSize } ?: return
                Zone.PROOFREAD -> proofreadLeft to proofreadRight
                Zone.MIC, Zone.CHIP_0, Zone.CHIP_1, Zone.CHIP_2 -> return // Drawn with their own pressed color.
                Zone.CUSTOM -> {
                    val (left, bottom) = boxOf(customButtons.indexOfFirst { it.id == pressed.id }) ?: return
                    top = (bottom - rowHeight).toFloat()
                    left to left + slotSize
                }
                else -> {
                    val left = slotsLeft + SLOT_ZONES.indexOf(pressed.zone) * slotWidth
                    left to left + slotWidth
                }
            }
        val inset = rowHeight * PRESSED_INSET
        val side = minOf(inset, (right - left) * PRESSED_INSET)
        val radius = rowHeight / 2f
        canvas.drawRoundRect(
            left + side,
            top + inset,
            right - side,
            top + rowHeight - inset,
            radius,
            radius,
            pressedPaint,
        )
    }

    private fun drawIcon(
        canvas: Canvas,
        @DrawableRes id: Int,
        centerX: Float,
        tint: Int,
        centerY: Float = rowHeight / 2f,
    ) {
        val half = (theme.stripIconSize / 2f).toInt()
        val x = centerX.toInt()
        val y = centerY.toInt()
        theme.icon(id, tint).apply {
            setBounds(x - half, y - half, x + half, y + half)
            draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() is called for every completed tap.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hit = hitAt(event.x, event.y)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            undoPeek.end()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tap.down(event)
                setPressed(hit)
                val holdable =
                    when (hit.zone) {
                        Zone.CUSTOM -> customButtons.firstOrNull { it.id == hit.id }?.holds == true
                        Zone.PROOFREAD ->
                            proofreadState == ProofreadState.UNDO ||
                                (proofreadHolds && proofreadState == ProofreadState.IDLE)
                        else -> false
                    }
                if (holdable) postDelayed(holdAction, HOLD_MILLIS)
            }
            MotionEvent.ACTION_MOVE -> if (!tap.keeps(event, pressed, hit, ::hitAt)) setPressed(Hit.NONE)
            MotionEvent.ACTION_UP -> {
                val tapped = pressed.takeIf { tap.keeps(event, it, hit, ::hitAt) } ?: Hit.NONE
                setPressed(Hit.NONE)
                if (tapped != Hit.NONE) {
                    performClick()
                    dispatch(tapped, event.downTime)
                }
            }
            MotionEvent.ACTION_CANCEL -> setPressed(Hit.NONE)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dispatch(
        hit: Hit,
        downTime: Long,
    ) {
        val listener = listener ?: return
        when (val zone = hit.zone) {
            Zone.LEFT_BUTTON -> if (voiceState.active) listener.onVoiceCancel() else listener.onSettingsClicked()
            Zone.CLIPBOARD -> listener.onClipboardClicked()
            Zone.FULLSCREEN -> listener.onFullscreenClicked()
            Zone.PROOFREAD -> listener.onProofreadClicked()
            Zone.MIC -> listener.onMicClicked()
            Zone.CHIP_0, Zone.CHIP_1, Zone.CHIP_2 ->
                clipChips.chips
                    .getOrNull(
                        CHIP_ZONES.indexOf(zone),
                    )?.let(listener::onChipClicked)
            Zone.NONE -> Unit
            Zone.CUSTOM -> {
                val index = customButtons.indexOfFirst { it.id == hit.id }
                val button = customButtons.getOrNull(index) ?: return
                if (!button.group) return listener.onCustomButtonUsed(button.id, held = false)
                val (left, bottom) = boxOf(index) ?: return
                groupPopup.toggle(this, button, (left + slotSize / 2f).toInt(), bottom, downTime)
            }
            else -> wordIn(zone, suggestions)?.let(listener::onSuggestionPicked)
        }
    }

    /** Whether the popup of a group is open. */
    internal val groupShown: Boolean get() = groupPopup.isShowing

    /**
     * The popup of a group takes no focus, so it closes itself when the keyboard hides or a panel covers the strip,
     * or it would show up again with the keyboard.
     */
    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) groupPopup.dismiss()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        groupPopup.dismiss()
        undoPeek.end()
        removeCallbacks(holdAction)
        removeCallbacks(hideSuggestions)
    }

    /** Any change of the pressed zone ends the wait for a hold. */
    private fun setPressed(hit: Hit) {
        if (hit == pressed) return
        removeCallbacks(holdAction)
        pressed = hit
        invalidate()
    }

    private companion object {
        const val SLOTS = 3
        val SLOT_ZONES = listOf(Zone.SLOT_LEFT, Zone.SLOT_MIDDLE, Zone.SLOT_RIGHT)
        const val TEXT_WIDTH = 0.9f
        const val DIVIDER_HEIGHT = 0.4f
        const val PRESSED_INSET = 0.12f
        const val ACTIVE_RADIUS = 0.36f
        const val HALO_SCALE = 1.3f
        val CHIP_ZONES = listOf(Zone.CHIP_0, Zone.CHIP_1, Zone.CHIP_2)

        /** A button keeps at least this many icon widths, so that neighbouring icons do not touch. */
        const val MIN_ICON_ROOM = 1.2f

        /** As long as a key of the keyboard waits for its long press. */
        const val HOLD_MILLIS = 350L
        const val HALF_LUMINANCE = 0.5

        /** Next to custom buttons, a chip narrower than this many buttons would say nothing. */
        const val MIN_CHIP_ROOM = 2f
        const val UNITS_PER_ICON = 3f
        const val MIC_RADIUS = 0.4f
    }
}

private fun VoiceState.statusText(): Int =
    when (phase) {
        VoiceState.Phase.RECOGNIZING -> R.string.voice_status_recognizing
        VoiceState.Phase.ENHANCING -> R.string.voice_status_enhancing
        else ->
            when {
                preparing -> R.string.voice_status_preparing
                speech -> R.string.voice_status_speech
                else -> R.string.voice_status_listening
            }
    }

/** Slot order is left, middle, right; suggestion order is primary, second, third. */
private fun wordIn(
    zone: SuggestionStripView.Zone,
    suggestions: List<String>,
): String? =
    when (zone) {
        SuggestionStripView.Zone.SLOT_MIDDLE -> suggestions.getOrNull(0)
        SuggestionStripView.Zone.SLOT_LEFT -> suggestions.getOrNull(1)
        SuggestionStripView.Zone.SLOT_RIGHT -> suggestions.getOrNull(2)
        else -> null
    }
