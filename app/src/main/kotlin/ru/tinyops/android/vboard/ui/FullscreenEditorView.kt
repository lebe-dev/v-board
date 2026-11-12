package ru.tinyops.android.vboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.ExtractEditText
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonPlaces
import ru.tinyops.android.vboard.voice.ProofreadState

/**
 * The area above the keys while the keyboard takes the whole screen: a panel of actions and the text of
 * the field. The text is the system's [ExtractEditText], which the input method service keeps in step with
 * the field: it finds it by its reserved id.
 */
@SuppressLint("ViewConstructor")
class FullscreenEditorView(
    context: Context,
    private val theme: KeyboardTheme,
) : LinearLayout(context) {
    interface Listener {
        fun onEditorSelectAll()

        fun onEditorUndo()

        /** The selection, or the whole text without one. */
        fun onEditorCopy(text: CharSequence)

        fun onEditorClose()

        /** A tap on a custom button of the panel, or a hold when [held]. */
        fun onEditorButton(
            id: String,
            held: Boolean,
        )
    }

    var listener: Listener? = null

    /** The custom buttons meant for the panel, placed between its own; as many as fit are shown. */
    var customButtons: List<StripButton> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            showCustomButtons()
        }

    /** Shows what an undo would leave in the field while an undo arrow is held. */
    internal val undoPreview = UndoPreviewPopup(context, theme)

    private val groupPopup =
        ButtonGroupPopup(context, theme, undoPreview) { id, held -> listener?.onEditorButton(id, held) }

    private val customRow = PanelButtonRow(context, theme.suggestionHeight.toInt())

    /** Light icons mean a dark keyboard, whichever theme it comes from. */
    private val darkTheme = ColorUtils.calculateLuminance(theme.stripIcon) > HALF_LUMINANCE

    /** Makes the system spelling window light or dark like the keyboard. */
    private fun systemTheme() =
        if (darkTheme) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light

    private val editor =
        ExtractEditText(ContextThemeWrapper(context, systemTheme())).apply {
            id = android.R.id.inputExtractEditText
            background = null
            gravity = Gravity.TOP or Gravity.START
            isVerticalScrollBarEnabled = true
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.suggestionTextSize)
            setTextColor(theme.label)
            setHintTextColor(theme.hint)
            highlightColor = ColorUtils.setAlphaComponent(theme.label, SELECTION_ALPHA)
            textCursorDrawable =
                GradientDrawable().apply {
                    setColor(theme.label)
                    setSize(dp(CURSOR_WIDTH_DP), 0)
                }
            val padding = dp(TEXT_PADDING_DP)
            setPadding(padding, 0, padding, padding)
        }

    init {
        orientation = VERTICAL
        background = theme.backgroundDrawable()
        addView(panel(), LayoutParams(MATCH_PARENT, theme.suggestionHeight.toInt()))
        addView(editor, LayoutParams(MATCH_PARENT, 0, 1f))
        // The window starts below the status bar on its own, but a cutout or a landscape bar may remain.
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
    }

    private fun panel() =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(button(R.drawable.ic_select_all, R.string.fullscreen_select_all) { onEditorSelectAll() })
            addView(
                button(R.drawable.ic_undo, R.string.fullscreen_undo) { onEditorUndo() }.also {
                    it.previewOnHold(UNDO_ID)
                },
            )
            addView(button(R.drawable.ic_content_copy, R.string.fullscreen_copy) { onEditorCopy(chosenText()) })
            addView(customRow, LayoutParams(0, MATCH_PARENT, 1f))
            addView(button(R.drawable.ic_fullscreen_exit, R.string.fullscreen_close) { onEditorClose() })
        }

    private fun button(
        @DrawableRes icon: Int,
        @StringRes description: Int,
        onClick: Listener.() -> Unit,
    ): View =
        iconButton(theme.icon(icon, theme.stripIcon), context.getString(description)).apply {
            setOnClickListener { listener?.onClick() }
        }

    private fun iconButton(
        icon: Drawable,
        description: String,
    ): ImageView {
        val size = theme.suggestionHeight.toInt()
        val padding = (size - theme.stripIconSize.toInt()) / 2
        return ImageView(context).apply {
            layoutParams = LayoutParams(size, size)
            setPadding(padding, padding, padding, padding)
            setImageDrawable(icon)
            contentDescription = description
            background = RippleDrawable(ColorStateList.valueOf(theme.suggestionPressed), null, null)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        groupPopup.dismiss()
        undoPreview.dismiss()
    }

    private fun showCustomButtons() {
        customRow.removeAllViews()
        customRow.placements = customButtons.map { it.placement }
        customButtons.forEach { customRow.addView(customButton(it)) }
    }

    private fun customButton(button: StripButton): View {
        val tint = button.color?.tint(darkTheme) ?: theme.stripIcon
        return iconButton(stateIcon(button, tint), button.description).apply {
            setOnClickListener {
                if (button.group) {
                    groupPopup.show(this, button, width / 2, height)
                } else {
                    listener?.onEditorButton(button.id, held = false)
                }
            }
            if (!button.holds) return@apply
            if (button.state == ProofreadState.UNDO) return@apply previewOnHold(button.id)
            setOnLongClickListener {
                listener?.onEditorButton(button.id, held = true)
                true
            }
        }
    }

    /**
     * A hold on an undo arrow, the panel's own or a custom one, previews the undo at the bottom of the text
     * area, just above the keys and far from the finger on the panel, until the finger lets go.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.previewOnHold(id: String) {
        setOnLongClickListener {
            undoPreview.show(this@FullscreenEditorView, id, atBottom = true)
            true
        }
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                undoPreview.dismiss()
            }
            false
        }
    }

    /** Its icon, a spinner during the request or the undo arrow after it, as on the strip. */
    private fun stateIcon(
        button: StripButton,
        tint: Int,
    ): Drawable =
        when (button.state) {
            ProofreadState.WORKING -> SpinnerDrawable(tint, theme.stripIconSize)
            ProofreadState.UNDO -> ownIcon(R.drawable.ic_undo, tint)
            ProofreadState.IDLE -> ownIcon(button.icon, tint)
        }

    /** A copy of its own, because the theme shares one drawable per icon and these differ in color. */
    private fun ownIcon(
        @DrawableRes icon: Int,
        tint: Int,
    ): Drawable = requireNotNull(context.getDrawable(icon)).mutate().apply { setTint(tint) }

    private fun chosenText(): CharSequence {
        val text = editor.text ?: return ""
        val start = editor.selectionStart
        val end = editor.selectionEnd
        return if (start in 0 until end) text.subSequence(start, end) else text
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** The id the undo preview is asked for when the panel's own undo button is held. */
        const val UNDO_ID = "fullscreen-undo"

        private const val TEXT_PADDING_DP = 16
        private const val CURSOR_WIDTH_DP = 2
        private const val SELECTION_ALPHA = 0x55
        private const val HALF_LUMINANCE = 0.5
    }
}

/** The free part of the panel: its children, squares of [size], stand by [placements] like the buttons on the strip. */
@SuppressLint("ViewConstructor")
private class PanelButtonRow(
    context: Context,
    private val size: Int,
) : ViewGroup(context) {
    var placements: List<ButtonPlacement> = emptyList()

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val exact = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) getChildAt(index).measure(exact, exact)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), size)
    }

    /** A button without a place gets no area, so a finger or a screen reader cannot find it. */
    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val places = ButtonPlaces.of(placements, 0f, (right - left).toFloat(), size.toFloat())
        for (index in 0 until childCount) {
            val start = places.lefts.getOrNull(index)?.toInt()
            if (start == null) {
                getChildAt(index).layout(0, 0, 0, 0)
            } else {
                getChildAt(index).layout(start, 0, start + size, size)
            }
        }
    }
}
