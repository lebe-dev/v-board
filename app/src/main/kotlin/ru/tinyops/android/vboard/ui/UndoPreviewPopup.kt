package ru.tinyops.android.vboard.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import ru.tinyops.android.vboard.R

/**
 * The small window near the strip that shows what an undo would leave in the field, for as long as the
 * undo arrow is held. It takes no touches, so the finger stays with the strip. Main thread only.
 */
internal class UndoPreviewPopup(
    private val context: Context,
    private val theme: KeyboardTheme,
) {
    private val density = context.resources.displayMetrics.density
    private var window: PopupWindow? = null

    /** What the field would hold after the undo of the button with an id; null when there is nothing to show. */
    var source: ((String) -> String?)? = null

    val isShowing: Boolean get() = window?.isShowing == true

    /**
     * Opens the window of the button [id] above [anchor], or inside it along its bottom edge when [atBottom]
     * (for the text area of the fullscreen mode, above the keys), as wide as the anchor; an empty text is told as such.
     */
    fun show(
        anchor: View,
        id: String,
        atBottom: Boolean = false,
    ) {
        dismiss()
        val text = source?.invoke(id)
        if (text == null || anchor.width <= 0) return
        val content = content(text)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popup =
            PopupWindow(content, anchor.width, content.measuredHeight, false).apply {
                isTouchable = false
                elevation = density * ELEVATION_DP
                setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            }
        window = popup
        // Away from the finger on the arrow, so that it does not cover the window.
        val offset = if (atBottom) 0 else anchor.height
        popup.showAsDropDown(anchor, 0, -(offset + content.measuredHeight))
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    private fun content(text: String): View {
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        // The color of the group window and the divider of the strip, opaque so that the text
                        // behind the window does not show through, and so the window fits any theme.
                        setColor(ColorUtils.setAlphaComponent(theme.raised, OPAQUE))
                        setStroke(dp(1), theme.suggestionDivider)
                        cornerRadius = density * RADIUS_DP
                    }
                setPadding(dp(SIDE_DP), dp(VERTICAL_DP), dp(SIDE_DP), dp(VERTICAL_DP))
            }
        column.addView(
            TextView(context).apply {
                if (text.isEmpty()) setText(R.string.undo_preview_empty) else this.text = text.take(MAX_CHARS)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
                setTextColor(if (text.isEmpty()) theme.hint else theme.suggestion)
                maxLines = MAX_LINES
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        return column
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val OPAQUE = 0xFF
        const val ELEVATION_DP = 8f
        const val RADIUS_DP = 12f
        const val SIDE_DP = 16
        const val VERTICAL_DP = 12
        const val TEXT_SIZE_SP = 16f
        const val MAX_LINES = 8
        const val MAX_CHARS = 800
    }
}
