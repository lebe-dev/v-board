package ru.tinyops.android.vboard.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A mode of the menu a hold on the microphone opens. */
data class MicMenuItem(
    val id: String,
    val label: String,
)

/**
 * The menu a hold on the microphone opens: the modes in a column right above the strip, at its right edge
 * over the microphone. The finger that holds the microphone stays on the strip, which passes every move here;
 * the mode under the finger is highlighted and the release picks it. The window takes no touches and no focus.
 * Main thread only.
 */
internal class MicModePopup(
    private val context: Context,
    private val theme: KeyboardTheme,
) {
    private val density = context.resources.displayMetrics.density
    private val rowHeight = theme.suggestionHeight.toInt()
    private var window: PopupWindow? = null
    private var items: List<MicMenuItem> = emptyList()
    private var rows: List<View> = emptyList()

    /** The menu in the coordinates of the anchor: it ends at the anchor's top. */
    private var left = 0
    private var right = 0
    private var top = 0

    /** The highlighted mode, -1 for none. */
    private var selected = -1

    val isShowing: Boolean get() = window != null

    fun show(
        anchor: View,
        modes: List<MicMenuItem>,
    ) {
        dismiss()
        if (modes.isEmpty()) return
        items = modes
        rows = modes.map(::row)
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        // Above the keyboard the window covers the app, which must not show through.
                        setColor(ColorUtils.setAlphaComponent(theme.raised, OPAQUE))
                        setStroke(dp(1), theme.suggestionDivider)
                        cornerRadius = density * RADIUS_DP
                    }
                clipToOutline = true
                rows.forEach { addView(it, LinearLayout.LayoutParams(MATCH, rowHeight)) }
            }
        column.measure(
            View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(rowHeight * modes.size, View.MeasureSpec.EXACTLY),
        )
        val width = column.measuredWidth.coerceIn(min(dp(MIN_WIDTH_DP), anchor.width), anchor.width)
        val height = rowHeight * modes.size
        right = anchor.width
        left = max(0, right - width)
        top = -height
        selected = -1
        window =
            PopupWindow(column, width, height, false).apply {
                isTouchable = false
                elevation = density * ELEVATION_DP
                setBackgroundDrawable(null)
                showAsDropDown(anchor, left, -anchor.height - height)
            }
    }

    /** The finger is at [x], [y] of the anchor: the mode under it is highlighted. */
    fun track(
        x: Float,
        y: Float,
    ) {
        val index = indexAt(x, y)
        if (index == selected) return
        rows.getOrNull(selected)?.isActivated = false
        selected = index
        rows.getOrNull(index)?.isActivated = true
    }

    /** The finger let go at [x], [y] of the anchor: closes the menu and returns the mode it picked, if any. */
    fun release(
        x: Float,
        y: Float,
    ): String? {
        val picked = items.getOrNull(indexAt(x, y))?.id
        dismiss()
        return picked
    }

    fun dismiss() {
        window?.dismiss()
        window = null
        items = emptyList()
        rows = emptyList()
        selected = -1
    }

    /** The mode at [x], [y] of the anchor, -1 outside the menu. */
    private fun indexAt(
        x: Float,
        y: Float,
    ): Int {
        if (x < left || x > right) return -1
        if (y < top || y >= 0f) return -1
        return floor((y - top) / rowHeight).toInt().coerceIn(0, items.lastIndex)
    }

    private fun row(item: MicMenuItem): View =
        TextView(context).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SIZE_SP)
            setTextColor(theme.suggestion)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE_DP), 0, dp(SIDE_DP), 0)
            background =
                StateListDrawable().apply {
                    val highlight = GradientDrawable().apply { setColor(theme.suggestionPressed) }
                    addState(intArrayOf(android.R.attr.state_activated), highlight)
                }
        }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val ELEVATION_DP = 8f
        const val RADIUS_DP = 12f
        const val MIN_WIDTH_DP = 200
        const val SIDE_DP = 16
        const val NAME_SIZE_SP = 16f
        const val OPAQUE = 255
    }
}
