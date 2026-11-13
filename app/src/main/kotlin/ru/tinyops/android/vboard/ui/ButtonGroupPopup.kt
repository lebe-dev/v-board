package ru.tinyops.android.vboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.voice.ProofreadState
import kotlin.math.max
import kotlin.math.min

/**
 * The small window a button group opens: the name of the group, then its members in a list, each with
 * its icon and its name, a row as high as the strip. A tap on a member closes the window and passes the
 * gesture on, so does a hold on a member that has one. A member with a list of its own, as a quick replies button,
 * opens that list in the same place. A long list scrolls. The window takes no focus from the
 * field, and a touch outside it closes it and still reaches what it touched. Main thread only.
 *
 * @param onGesture a tap on the member [StripButton.id], or a hold when `held`
 */
internal class ButtonGroupPopup(
    private val context: Context,
    private val theme: KeyboardTheme,
    private val undoPreview: UndoPreviewPopup = UndoPreviewPopup(context, theme),
    private val onGesture: (id: String, held: Boolean) -> Unit,
) {
    private val darkTheme = ColorUtils.calculateLuminance(theme.stripIcon) > HALF_LUMINANCE
    private val density = context.resources.displayMetrics.density
    private val size = theme.suggestionHeight.toInt()
    private var window: PopupWindow? = null

    /** The group whose window is open. */
    private var shownId: String? = null

    /** Where the open window was asked to stand, for the list of a member that replaces it. */
    private var place: Place? = null

    private class Place(
        val anchor: View,
        val centerX: Int,
        val bottom: Int,
    )

    /** The group whose window a touch outside it closed last, and when. */
    private var closedId: String? = null
    private var closedAt = 0L

    val isShowing: Boolean get() = window?.isShowing == true

    /**
     * A tap on [group] that came down at [downTime]: it opens the window as [show] does, or closes it when the
     * window of this group is open or a touch outside it has closed it since then.
     */
    fun toggle(
        anchor: View,
        group: StripButton,
        centerX: Int,
        bottom: Int,
        downTime: Long,
    ) {
        if (isShowing && shownId == group.id) {
            dismiss()
            return
        }
        if (closedId == group.id && closedAt >= downTime) return
        show(anchor, group, centerX, bottom)
    }

    /**
     * Opens the window of [group] under [anchor], its middle at [centerX] of the anchor and its top [bottom]
     * pixels from the anchor's top, kept inside the anchor's width and the height of its window.
     */
    fun show(
        anchor: View,
        group: StripButton,
        centerX: Int,
        bottom: Int,
    ) {
        dismiss()
        if (group.members.isEmpty()) return
        val content = content(group)
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val room = anchor.rootView.height - location[1] - bottom
        val maxHeight = max(room, size * MIN_ROWS)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        val width = content.measuredWidth.coerceIn(min(dp(MIN_WIDTH_DP), anchor.width), anchor.width)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        val left = max(0, min(centerX - width / 2, anchor.width - width))
        val popup =
            // Focusable, it would take the focus from the field and keep the touch that closes it to itself.
            PopupWindow(content, width, content.measuredHeight, false).apply {
                isOutsideTouchable = true
                elevation = density * ELEVATION_DP
                setBackgroundDrawable(null)
                setOnDismissListener {
                    window = null
                    shownId = null
                    closedId = group.id
                    closedAt = SystemClock.uptimeMillis()
                }
            }
        window = popup
        shownId = group.id
        this.place = Place(anchor, centerX, bottom)
        popup.showAsDropDown(anchor, left, bottom - anchor.height)
    }

    /** Closes the window on purpose, which is no touch outside it. */
    fun dismiss() {
        val popup = window ?: return
        window = null
        shownId = null
        popup.setOnDismissListener(null)
        popup.dismiss()
        undoPreview.dismiss()
    }

    /** The title of [group] and the list of its members. */
    fun content(group: StripButton): View {
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        setColor(theme.raised)
                        cornerRadius = density * RADIUS_DP
                    }
                clipToOutline = true
            }
        val title =
            TextView(context).apply {
                text = group.description
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SIZE_SP)
                setTextColor(theme.hint)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(SIDE_DP), dp(TITLE_TOP_DP), dp(SIDE_DP), dp(TITLE_BOTTOM_DP))
            }
        column.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        group.members.forEach {
            list.addView(item(it, column), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, size))
        }
        list.setPadding(0, 0, 0, dp(LIST_BOTTOM_DP))
        val scroller =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP))
            }
        column.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))
        return column
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun item(
        button: StripButton,
        column: View,
    ): View {
        val tint = button.color?.tint(darkTheme) ?: theme.stripIcon
        val padding = (size - theme.stripIconSize.toInt()) / 2
        val icon =
            ImageView(context).apply {
                setPadding(padding, padding, padding, padding)
                setImageDrawable(stateIcon(button, tint))
            }
        val name =
            TextView(context).apply {
                text = button.description
                setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SIZE_SP)
                setTextColor(theme.suggestion)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ICON_START_DP), 0, dp(SIDE_DP), 0)
            addView(icon, LinearLayout.LayoutParams(size, size))
            addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(NAME_GAP_DP) })
            background =
                RippleDrawable(ColorStateList.valueOf(theme.suggestionPressed), null, Color.WHITE.toDrawable())
            setOnClickListener {
                val at = place
                if (button.group && at != null) return@setOnClickListener show(at.anchor, button, at.centerX, at.bottom)
                dismiss()
                onGesture(button.id, false)
            }
            if (!button.holds) return@apply
            if (button.state == ProofreadState.UNDO) return@apply previewOnHold(button.id, column)
            setOnLongClickListener {
                dismiss()
                onGesture(button.id, true)
                true
            }
        }
    }

    /** A hold on the undo arrow of a member previews the undo above the window until the finger lets go. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.previewOnHold(
        id: String,
        column: View,
    ) {
        setOnLongClickListener {
            undoPreview.show(column, id)
            true
        }
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                undoPreview.dismiss()
            }
            false
        }
    }

    /** A spinner during the request, the undo arrow after it, as on the strip. */
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

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ELEVATION_DP = 8f
        const val RADIUS_DP = 12f
        const val HALF_LUMINANCE = 0.5
        const val MIN_ROWS = 3
        const val MIN_WIDTH_DP = 200
        const val SIDE_DP = 16
        const val ICON_START_DP = 4
        const val NAME_GAP_DP = 4
        const val TITLE_TOP_DP = 12
        const val TITLE_BOTTOM_DP = 4
        const val LIST_BOTTOM_DP = 4
        const val TITLE_SIZE_SP = 14f
        const val NAME_SIZE_SP = 16f
    }
}
