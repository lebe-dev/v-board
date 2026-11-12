package ru.tinyops.android.vboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.emoji.EmojiHidden
import ru.tinyops.android.vboard.core.emoji.EmojiPins
import ru.tinyops.android.vboard.core.emoji.EmojiRecents
import ru.tinyops.android.vboard.emoji.EmojiAssets
import ru.tinyops.android.vboard.emoji.EmojiPrefs
import ru.tinyops.android.vboard.emoji.chipTitle
import kotlin.math.max

/**
 * The emoji picker that replaces the keys: text chips on top and a scrolling grid under them. The
 * first chip shows only the user's own emoji, the pinned ones on plates and then the recent ones;
 * the other chips lead to their categories in one grid of the whole catalog. The way back to the
 * letters and backspace float over the bottom of the grid.
 * Holding an emoji opens a small popup to pin or hide it; carrying a pinned one moves it.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(
    context: Context,
    private val theme: KeyboardTheme,
    private val assets: EmojiAssets,
    private val prefs: EmojiPrefs,
) : FrameLayout(context) {
    interface Listener {
        fun onEmojiBack()

        fun onEmojiPick(text: String)

        fun onBackspace()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val chipRow = LinearLayout(context)
    private val chipScroller =
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }
    private val grid = EmojiGridView(context, theme, assets)
    private val popupLayer = FrameLayout(context)
    private val popup = LinearLayout(context)
    private val heldTile = FrameLayout(context)
    private var pins = EmojiPins()
    private var hidden = EmojiHidden()
    private var recents = EmojiRecents()
    private var showingMine = true
    private var catalogSections: List<EmojiSection> = emptyList()

    // The chip of the user's own emoji comes before those of the categories, while it exists.
    private var firstCategoryChip = 0
    private val backLabel = TextView(context)
    private val repeat = Runnable { repeatBackspace() }

    init {
        chipRow.setPadding(dp(SIDE), 0, dp(SIDE), 0)
        chipScroller.addView(chipRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        content.addView(
            chipScroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(CHIPS_HEIGHT)),
        )
        content.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addFloatingButtons()
        addPopupLayer()
        grid.onPick = ::pick
        grid.onHold = ::showPopup
        grid.onDragStart = ::dismissPopup
        grid.onPinsReordered = { shown ->
            pins = pins.reordered(shown)
            prefs.pins = pins
        }
        grid.onSectionChanged = { section -> if (!showingMine) select(section + firstCategoryChip) }
    }

    /** Whether a picked emoji goes among the recent ones; an incognito field keeps no trace of it. */
    private var rememberRecents = true

    /**
     * Shows the picker from its start. The user's own emoji are built here and are not reordered by
     * use while the picker is open, so the emoji under a finger stay where they are.
     */
    fun open(
        backText: String,
        rememberRecents: Boolean = true,
    ) {
        this.rememberRecents = rememberRecents
        backLabel.text = backText
        hidden = prefs.hidden
        pins = prefs.pins
        recents = prefs.recents
        showingMine = true
        dismissPopup()
        rebuild(keepScroll = false)
    }

    private fun rebuild(keepScroll: Boolean) {
        val catalog = assets.catalog
        val categories = hidden.categoriesIn(catalog)
        val mine = mineSections()
        catalogSections =
            categories.map { category ->
                EmojiSection(hidden.emojiIn(catalog, category).map { EmojiCell(it.text, it.image) })
            }
        firstCategoryChip = if (mine.isEmpty()) 0 else 1
        val staysOnPage = keepScroll && (!showingMine || mine.isNotEmpty())
        showingMine = showingMine && mine.isNotEmpty()
        val titles =
            listOfNotNull(R.string.emoji_chip_mine.takeIf { mine.isNotEmpty() }) + categories.map { it.chipTitle }
        chipRow.removeAllViews()
        val chipParams = { LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT) }
        for ((index, title) in titles.withIndex()) chipRow.addView(chip(title, index), chipParams())
        grid.pinned = pins.items.toSet()
        grid.setSections(if (showingMine) mine else catalogSections, staysOnPage)
        select(if (showingMine) 0 else grid.section + firstCategoryChip)
    }

    /** The groups of the user's own emoji: the pinned ones on plates, the recent ones that are not pinned. */
    private fun mineSections(): List<EmojiSection> {
        val catalog = assets.catalog

        fun shown(items: List<String>) = items.filterNot { hidden.hides(catalog, it) }
        val pinned = shown(pins.items).map { EmojiCell(it, catalog.find(it)?.image, plated = true) }
        val recent = shown(pins.leftOutOf(recents.items)).map { EmojiCell(it, catalog.find(it)?.image) }
        return listOfNotNull(
            pinned.takeIf { it.isNotEmpty() }?.let {
                EmojiSection(it, context.getString(R.string.emoji_title_pinned), pinCount = it.size)
            },
            recent.takeIf { it.isNotEmpty() }?.let { EmojiSection(it, context.getString(R.string.emoji_title_recent)) },
        )
    }

    /** Opens the page of a chip: the user's own emoji, or the catalog at the category of the chip. */
    private fun openChip(index: Int) {
        val category = index - firstCategoryChip
        if (category < 0) {
            showingMine = true
            grid.setSections(mineSections())
        } else {
            if (showingMine) grid.setSections(catalogSections)
            showingMine = false
            grid.scrollToSection(category)
        }
        select(index)
    }

    private fun pick(text: String) {
        if (rememberRecents) prefs.recents = prefs.recents.use(text)
        listener?.onEmojiPick(text)
    }

    // region Chips

    private fun chip(
        @StringRes title: Int,
        index: Int,
    ): View =
        TextView(context).apply {
            text = context.getString(title)
            gravity = Gravity.CENTER
            textSize = LABEL_TEXT_SIZE
            typeface = MEDIUM
            setPadding(dp(CHIP_PADDING), 0, dp(CHIP_PADDING), 0)
            setOnClickListener { openChip(index) }
        }

    private fun select(index: Int) {
        for (i in 0 until chipRow.childCount) {
            val chip = chipRow.getChildAt(i) as TextView
            chip.setTextColor(if (i == index) theme.popupSelectedLabel else theme.hint)
            chip.background = if (i == index) chipPill() else null
            // A background brings its own padding, which replaces the padding of the view.
            chip.setPadding(dp(CHIP_PADDING), 0, dp(CHIP_PADDING), 0)
        }
        val chip = chipRow.getChildAt(index) ?: return
        chipScroller.smoothScrollTo(chip.left - (chipScroller.width - chip.width) / 2, 0)
    }

    private fun chipPill(): Drawable {
        val inset = dp((CHIPS_HEIGHT - CHIP_HEIGHT) / 2)
        return InsetDrawable(rounded(theme.popupSelected, dp(CHIP_HEIGHT / 2)), 0, inset, 0, inset)
    }

    // endregion

    // region Popup

    private fun addPopupLayer() {
        val light = ColorUtils.calculateLuminance(theme.background) > LIGHT_LUMINANCE
        popupLayer.visibility = GONE
        // The floating buttons have an elevation, and the dimming must cover them and their touches.
        popupLayer.elevation = dp(BUTTON_ELEVATION + 1).toFloat()
        popupLayer.outlineProvider = null
        popupLayer.setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, if (light) SCRIM_LIGHT else SCRIM_DARK))
        popupLayer.setOnClickListener { dismissPopup() }
        popup.setPadding(dp(POPUP_PADDING), dp(POPUP_PADDING), dp(POPUP_PADDING), dp(POPUP_PADDING))
        popup.background = rounded(theme.raised, dp(POPUP_RADIUS))
        popup.elevation = dp(POPUP_ELEVATION).toFloat()
        popupLayer.addView(popup, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        heldTile.background = rounded(theme.raised, dp(POPUP_RADIUS))
        heldTile.elevation = dp(POPUP_ELEVATION).toFloat()
        popupLayer.addView(heldTile, LayoutParams(dp(TILE), dp(TILE)))
        addView(popupLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The held emoji on a raised tile over a dimmed picker, with its actions above it or, near the top, below it. */
    private fun showPopup(
        text: String,
        anchor: RectF,
    ) {
        popup.removeAllViews()
        val pinnedNow = text in pins
        popup.addView(
            action(
                R.drawable.ic_push_pin,
                if (pinnedNow) R.string.emoji_unpin else R.string.emoji_pin,
                primary = true,
            ) {
                pins = pins.toggle(text)
                prefs.pins = pins
                rebuild(keepScroll = true)
            },
        )
        popup.addView(
            action(R.drawable.ic_visibility_off, R.string.emoji_hide, primary = false) {
                prefs.hidden = prefs.hidden.toggleEmoji(text)
                hidden = prefs.hidden
                rebuild(keepScroll = true)
            },
        )
        heldTile.removeAllViews()
        heldTile.addView(heldEmoji(text), LayoutParams(dp(TILE_PICTURE), dp(TILE_PICTURE), Gravity.CENTER))
        popup.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        place(anchor)
        popupLayer.visibility = VISIBLE
    }

    private fun place(anchor: RectF) {
        val tile = dp(TILE)
        val gap = dp(POPUP_GAP)
        val centerX = anchor.centerX() + grid.left + content.left
        val centerY = anchor.centerY() + grid.top + content.top
        val tileTop = (centerY - tile / 2f).toInt().coerceIn(0, max(0, height - tile))
        (heldTile.layoutParams as LayoutParams).apply {
            leftMargin = (centerX - tile / 2f).toInt().coerceIn(0, max(0, width - tile))
            topMargin = tileTop
            heldTile.layoutParams = this
        }
        val above = tileTop - popup.measuredHeight - gap
        (popup.layoutParams as LayoutParams).apply {
            leftMargin = (centerX - popup.measuredWidth / 2f).toInt().coerceIn(0, max(0, width - popup.measuredWidth))
            topMargin = if (above >= 0) above else tileTop + tile + gap
            popup.layoutParams = this
        }
    }

    private fun dismissPopup() {
        popupLayer.visibility = GONE
    }

    /** The picture of an emoji, or its system glyph when pictures are missing. */
    private fun heldEmoji(emoji: String): View {
        val bitmap =
            assets.catalog
                .find(emoji)
                ?.image
                ?.let(assets::picture)
        if (bitmap != null) return ImageView(context).apply { setImageBitmap(bitmap) }
        return TextView(context).apply {
            text = emoji
            gravity = Gravity.CENTER
            textSize = HELD_GLYPH_SIZE
        }
    }

    private fun action(
        @DrawableRes icon: Int,
        @StringRes label: Int,
        primary: Boolean,
        onClick: () -> Unit,
    ): View =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ACTION_HEIGHT)
            setPadding(dp(ACTION_PADDING), 0, dp(ACTION_PADDING), 0)
            if (primary) {
                val fill = ColorUtils.setAlphaComponent(theme.popupSelected, ACTION_FILL_ALPHA)
                background = rounded(fill, dp(ACTION_RADIUS))
            }
            addView(
                ImageView(context).apply {
                    setImageDrawable(requireNotNull(context.getDrawable(icon)).mutate().apply { setTint(theme.label) })
                },
                LinearLayout.LayoutParams(dp(ACTION_ICON), dp(ACTION_ICON)).apply { marginEnd = dp(ACTION_ICON_GAP) },
            )
            addView(
                TextView(context).apply {
                    text = context.getString(label)
                    textSize = LABEL_TEXT_SIZE
                    typeface = MEDIUM
                    setTextColor(theme.label)
                },
            )
            contentDescription = context.getString(label)
            setOnClickListener {
                dismissPopup()
                onClick()
            }
        }

    // endregion

    override fun onDetachedFromWindow() {
        removeCallbacks(repeat)
        super.onDetachedFromWindow()
    }

    // region Floating buttons

    private fun addFloatingButtons() {
        val fade =
            View(context).apply {
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            ColorUtils.setAlphaComponent(theme.background, 0),
                            ColorUtils.setAlphaComponent(theme.background, FADE_ALPHA),
                        ),
                    )
            }
        addView(fade, LayoutParams(LayoutParams.MATCH_PARENT, dp(FADE_HEIGHT), Gravity.BOTTOM))
        backLabel.apply {
            gravity = Gravity.CENTER
            textSize = BACK_TEXT_SIZE
            typeface = MEDIUM
            setTextColor(theme.label)
            background = rounded(theme.raised, dp(BUTTON_HEIGHT / 2))
            elevation = dp(BUTTON_ELEVATION).toFloat()
            contentDescription = context.getString(R.string.emoji_back)
            setOnClickListener { listener?.onEmojiBack() }
        }
        addView(backLabel, buttonParams(Gravity.BOTTOM or Gravity.START))
        addView(backspaceButton(), buttonParams(Gravity.BOTTOM or Gravity.END))
    }

    private fun buttonParams(gravity: Int) =
        LayoutParams(dp(BUTTON_WIDTH), dp(BUTTON_HEIGHT), gravity).apply {
            setMargins(dp(BUTTON_MARGIN), 0, dp(BUTTON_MARGIN), dp(BUTTON_MARGIN))
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun backspaceButton(): View =
        ImageView(context).apply {
            setImageDrawable(
                requireNotNull(context.getDrawable(R.drawable.ic_backspace)).mutate().apply { setTint(theme.label) },
            )
            val paddingX = dp(BACKSPACE_PADDING_X)
            val paddingY = dp(BACKSPACE_PADDING_Y)
            setPadding(paddingX, paddingY, paddingX, paddingY)
            background = rounded(theme.raised, dp(BUTTON_HEIGHT / 2))
            elevation = dp(BUTTON_ELEVATION).toFloat()
            contentDescription = context.getString(R.string.emoji_backspace)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        listener?.onBackspace()
                        postDelayed(repeat, REPEAT_DELAY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(repeat)
                }
                true
            }
        }

    private fun repeatBackspace() {
        listener?.onBackspace()
        postDelayed(repeat, REPEAT_INTERVAL)
    }

    // endregion

    private fun rounded(
        color: Int,
        radius: Int,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        const val LABEL_TEXT_SIZE = 13f
        const val SIDE = 8

        const val CHIPS_HEIGHT = 44
        const val CHIP_HEIGHT = 30
        const val CHIP_PADDING = 12

        const val LIGHT_LUMINANCE = 0.5
        const val SCRIM_DARK = 0x8C
        const val SCRIM_LIGHT = 0x47
        const val POPUP_RADIUS = 16
        const val POPUP_PADDING = 4
        const val POPUP_GAP = 8
        const val POPUP_ELEVATION = 8
        const val TILE = 56
        const val TILE_PICTURE = 44
        const val HELD_GLYPH_SIZE = 30f
        const val ACTION_HEIGHT = 44
        const val ACTION_PADDING = 12
        const val ACTION_RADIUS = 12
        const val ACTION_ICON = 18
        const val ACTION_ICON_GAP = 6
        const val ACTION_FILL_ALPHA = 0x38

        const val FADE_HEIGHT = 72
        const val FADE_ALPHA = 0xF2
        const val BUTTON_WIDTH = 72
        const val BUTTON_HEIGHT = 44
        const val BUTTON_MARGIN = 10
        const val BUTTON_ELEVATION = 4
        const val BACK_TEXT_SIZE = 14f
        const val BACKSPACE_PADDING_X = 25
        const val BACKSPACE_PADDING_Y = 11
        const val REPEAT_DELAY = 400L
        const val REPEAT_INTERVAL = 50L
    }
}
