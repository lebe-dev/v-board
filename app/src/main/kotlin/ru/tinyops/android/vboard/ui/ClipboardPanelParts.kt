package ru.tinyops.android.vboard.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.layout.KeyStyle

/** The small views of [ClipboardPanelView] that depend only on the theme: cards, labels, tabs and chips. */
internal class ClipboardPanelParts(
    private val context: Context,
    private val theme: KeyboardTheme,
) {
    private val density = context.resources.displayMetrics.density

    /** The menu of the card that was held last. */
    var menu: PopupWindow? = null
        private set

    fun dp(value: Int) = (value * density).toInt()

    fun tinted(
        @DrawableRes id: Int,
        color: Int,
    ) = requireNotNull(context.getDrawable(id)).mutate().apply { setTint(color) }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun cardParams() =
        LinearLayout
            .LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(GAP)
            }

    /**
     * A rounded card around [body]; in the delete mode it wears a cross. Outside that mode a hold on it
     * opens a menu with the one item that runs [onDelete].
     */
    fun card(
        body: View,
        editing: Boolean,
        onDelete: () -> Unit,
        onClick: () -> Unit,
    ): View =
        FrameLayout(context).apply {
            background = rounded(theme.keyColor(KeyStyle.LETTER, false), dp(CARD_RADIUS))
            clipToOutline = true
            outlineProvider = roundedOutline()
            addView(
                body,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
            if (editing) addView(deleteBadge(), badgeParams())
            setOnClickListener { onClick() }
            if (!editing) {
                setOnLongClickListener {
                    holdMenu(this, onDelete)
                    true
                }
            }
        }

    /** Opens the menu of a held card: its middle is over the card's middle. */
    private fun holdMenu(
        card: View,
        onDelete: () -> Unit,
    ) {
        val item = menuItem()
        val menu = menuWindow(item)
        this.menu = menu
        item.setOnClickListener {
            menu.dismiss()
            onDelete()
        }
        item.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val x = (card.width - item.measuredWidth) / 2
        val y = -(card.height + item.measuredHeight) / 2
        menu.showAsDropDown(card, x, y)
    }

    private fun menuItem(): TextView =
        TextView(context).apply {
            setText(R.string.clip_delete)
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MENU_TEXT_SIZE)
            setTextColor(theme.suggestion)
            minHeight = dp(MENU_HEIGHT)
            minWidth = dp(MENU_WIDTH)
            setPadding(dp(MENU_PADDING), 0, dp(MENU_PADDING), 0)
            background = rounded(theme.raised, dp(MENU_RADIUS))
        }

    private fun menuWindow(content: View): PopupWindow =
        PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = density * MENU_ELEVATION
            setBackgroundDrawable(null)
        }

    /** The small line above the text of a card that says where it came from. */
    fun caption(text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CAPTION_SIZE)
            setTextColor(theme.hint)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun label(
        @StringRes text: Int,
        clickable: Boolean,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE)
            setTextColor(if (clickable) theme.suggestion else theme.hint)
            setPadding(dp(SIDE + GAP), dp(GAP), dp(SIDE), dp(GAP))
            if (clickable) setOnClickListener { onClick() }
        }

    fun note(
        @StringRes text: Int,
    ): TextView =
        TextView(context).apply {
            setText(text)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NOTE_SIZE)
            setTextColor(theme.hint)
            setPadding(dp(SIDE * 2), dp(NOTE_TOP), dp(SIDE * 2), 0)
        }

    /** A tab of the header; [select] shows which one is open. */
    fun tab(
        @StringRes text: Int,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            setText(text)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SIZE)
            maxLines = 1
            setPadding(dp(TAB_PADDING), 0, dp(TAB_PADDING), 0)
            setOnClickListener { onClick() }
        }

    fun select(
        tab: TextView,
        selected: Boolean,
    ) {
        tab.isSelected = selected
        tab.setTextColor(if (selected) theme.suggestion else theme.hint)
        tab.background = if (selected) pill() else null
    }

    /** A filter chip: filled when [selected], outlined otherwise. */
    fun chip(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE)
            maxLines = 1
            setPadding(dp(CHIP_PADDING), 0, dp(CHIP_PADDING), 0)
            isSelected = selected
            setTextColor(if (selected) theme.suggestion else theme.hint)
            background =
                if (selected) pill() else rounded(0, dp(PILL_RADIUS)).apply { setStroke(dp(1), theme.hint) }
            setOnClickListener { onClick() }
        }

    private fun pill() = rounded(theme.keyColor(KeyStyle.LETTER, false), dp(PILL_RADIUS))

    private fun deleteBadge(): View =
        ImageView(context).apply {
            setImageDrawable(tinted(R.drawable.ic_close, theme.suggestion))
            setPadding(dp(BADGE_PADDING), dp(BADGE_PADDING), dp(BADGE_PADDING), dp(BADGE_PADDING))
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.background)
                }
        }

    private fun badgeParams() =
        FrameLayout.LayoutParams(dp(BADGE), dp(BADGE), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(GAP / 2), dp(GAP / 2), 0)
        }

    private fun rounded(
        color: Int,
        radius: Int,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun roundedOutline() =
        object : ViewOutlineProvider() {
            override fun getOutline(
                view: View,
                outline: Outline,
            ) = outline.setRoundRect(0, 0, view.width, view.height, dp(CARD_RADIUS).toFloat())
        }

    companion object {
        const val SIDE = 8
        const val GAP = 8
        private const val LABEL_SIZE = 14f
        private const val NOTE_SIZE = 16f
        private const val CAPTION_SIZE = 12f
        private const val CARD_RADIUS = 16
        private const val PILL_RADIUS = 32
        private const val BADGE = 28
        private const val BADGE_PADDING = 6
        private const val NOTE_TOP = 48
        private const val TAB_PADDING = 14
        private const val TAB_TEXT_SIZE = 16f
        private const val CHIP_PADDING = 12
        private const val MENU_TEXT_SIZE = 16f
        private const val MENU_HEIGHT = 48
        private const val MENU_WIDTH = 120
        private const val MENU_PADDING = 20
        private const val MENU_RADIUS = 12
        private const val MENU_ELEVATION = 8f
    }
}
