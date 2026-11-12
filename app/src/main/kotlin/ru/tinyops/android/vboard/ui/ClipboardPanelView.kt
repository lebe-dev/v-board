package ru.tinyops.android.vboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.clipboard.ClipboardHistoryStore
import ru.tinyops.android.vboard.clipboard.ClipboardPrefs
import ru.tinyops.android.vboard.clipboard.Thumbnails
import ru.tinyops.android.vboard.core.clipboard.ClipEntry
import ru.tinyops.android.vboard.core.clipboard.ClipExtractor
import ru.tinyops.android.vboard.core.clipboard.ClipKind
import ru.tinyops.android.vboard.core.clipboard.ClipPayload
import ru.tinyops.android.vboard.core.layout.KeyStyle
import ru.tinyops.android.vboard.core.voice.PromptFilter
import ru.tinyops.android.vboard.core.voice.PromptResult
import ru.tinyops.android.vboard.ui.ClipboardPanelParts.Companion.GAP
import ru.tinyops.android.vboard.ui.ClipboardPanelParts.Companion.SIDE
import ru.tinyops.android.vboard.voice.PromptResultStore
import java.util.concurrent.Executors

/**
 * The clipboard history that replaces the keys: a header with back, the history
 * switch and the delete mode, and the remembered items in two columns. A tap pastes an item (or
 * deletes it in the delete mode). While [promptResultsShown] says so, the header switches between
 * the clipboard and the buffer of prompt [results], which can be filtered by prompt; in the delete mode
 * the results stand in one column and are dragged by a handle into a new order.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(
    context: Context,
    private val theme: KeyboardTheme,
    private val store: ClipboardHistoryStore,
    private val prefs: ClipboardPrefs,
    private val results: PromptResultStore? = null,
    private val promptResultsShown: () -> Boolean = { false },
) : LinearLayout(context) {
    interface Listener {
        fun onClipboardBack()

        /** Pastes [entry]; a prompt result comes as a text entry with its id. */
        fun onClipboardPick(entry: ClipEntry)
    }

    var listener: Listener? = null

    private val parts = ClipboardPanelParts(context, theme)
    private val decoder = Executors.newSingleThreadExecutor()
    private var editing = false

    /** The buffer of prompt results is open instead of the clipboard; kept between openings. */
    private var showingResults = false
    private var filter: PromptFilter = PromptFilter.All

    private val toggleButton = circleButton(R.drawable.ic_toggle_on, R.string.clip_history_on) { toggleHistory() }
    private val editButton = circleButton(R.drawable.ic_edit, R.string.clip_edit) { toggleEditing() }
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroller =
        ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    private val title =
        TextView(context).apply {
            setText(R.string.clip_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SIZE)
            setTextColor(theme.suggestion)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    private val clipboardTab = parts.tab(R.string.clip_tab_clipboard) { openTab(results = false) }
    private val resultsTab = parts.tab(R.string.clip_tab_prompts) { openTab(results = true) }
    private val tabs =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(clipboardTab, LayoutParams(LayoutParams.WRAP_CONTENT, dp(TAB_HEIGHT)))
            val second = LayoutParams(LayoutParams.WRAP_CONTENT, dp(TAB_HEIGHT))
            second.marginStart = dp(GAP / 2)
            addView(resultsTab, second)
        }

    init {
        orientation = VERTICAL
        addView(header(), LayoutParams(LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT)))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    /** The content of the menu of the card that was held last, for tests. */
    internal val heldMenu: View? get() = parts.menu?.contentView

    /** Shows the panel in its initial state: not editing, current history. */
    fun open() {
        editing = false
        refresh()
    }

    /** Rebuilds the list from the store. */
    fun refresh() {
        val resultsOn = results != null && promptResultsShown()
        if (!resultsOn) showingResults = false
        title.visibility = if (resultsOn) GONE else VISIBLE
        tabs.visibility = if (resultsOn) VISIBLE else GONE
        updateButtons()
        content.removeAllViews()
        if (showingResults) {
            showResults()
            return
        }
        val entries = store.history.entries
        when {
            !prefs.keepHistory -> content.addView(parts.note(R.string.clip_off))
            entries.isEmpty() -> content.addView(parts.note(R.string.clip_empty))
            else -> showEntries(entries)
        }
    }

    override fun onDetachedFromWindow() {
        decoder.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun showEntries(entries: List<ClipEntry>) {
        content.addView(
            parts.label(if (editing) R.string.clip_clear_all else R.string.clip_recent, clickable = editing) {
                store.clear()
            },
        )
        showColumns(entries, ::card, ::estimatedHeight)
    }

    private fun showResults() {
        val history = results?.history ?: return
        if (history.entries.isEmpty()) {
            content.addView(parts.note(R.string.prompt_results_empty))
            return
        }
        filter = history.available(filter)
        if (editing) {
            content.addView(parts.label(R.string.clip_clear_all, clickable = true) { results.clear() })
            showReorderable(history.matching(filter))
            return
        }
        content.addView(filterRow(history.filters()))
        showColumns(history.matching(filter), ::resultCard) { estimatedTextHeight(it.text) + CAPTION_HEIGHT }
    }

    /** The results in one column, each with a handle to drag it by and the cross to delete it. */
    private fun showReorderable(items: List<PromptResult>) {
        val column = parts.column().apply { setPadding(dp(SIDE), 0, dp(SIDE), dp(SIDE)) }
        val drag =
            ReorderDrag<Long>(column, scroller, dp(LIFT).toFloat(), dp(SCROLL_EDGE)) { ids -> results?.reorder(ids) }
        for (result in items) {
            val handle =
                ImageView(context).apply {
                    setImageDrawable(parts.tinted(R.drawable.ic_drag_handle, theme.hint))
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = context.getString(R.string.prompt_results_drag)
                }
            val row =
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    tag = result.id
                    addView(handle, LayoutParams(dp(HANDLE), LayoutParams.MATCH_PARENT))
                    addView(resultCard(result), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
            column.addView(row, parts.cardParams())
            drag.attach(handle, row)
        }
        content.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** The cards in two columns, each next card under the shorter one. */
    private fun <T> showColumns(
        items: List<T>,
        card: (T) -> View,
        height: (T) -> Float,
    ) {
        val columns = listOf(parts.column(), parts.column())
        val heights = FloatArray(columns.size)
        for (item in items) {
            val target = if (heights[0] <= heights[1]) 0 else 1
            columns[target].addView(card(item), parts.cardParams())
            heights[target] += height(item)
        }
        val row = LinearLayout(context).apply { setPadding(dp(SIDE), 0, dp(SIDE), dp(SIDE)) }
        for ((index, column) in columns.withIndex()) {
            val params = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            if (index == 0) params.marginEnd = dp(GAP / 2)
            if (index == 1) params.marginStart = dp(GAP / 2)
            row.addView(column, params)
        }
        content.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // region Header

    private fun header(): View =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE), 0, dp(SIDE), 0)
            addView(
                circleButton(R.drawable.ic_arrow_back, R.string.settings_back) { listener?.onClipboardBack() },
                buttonParams(),
            )
            val middle =
                FrameLayout(context).apply {
                    val wide = FrameLayout.LayoutParams.MATCH_PARENT
                    val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
                    addView(title, FrameLayout.LayoutParams(wide, wrap, Gravity.CENTER_VERTICAL))
                    addView(tabs, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER_VERTICAL))
                }
            addView(middle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(GAP * 2) })
            addView(toggleButton, buttonParams())
            addView(editButton, buttonParams().apply { marginStart = dp(GAP) })
        }

    private fun buttonParams() = LayoutParams(dp(BUTTON), dp(BUTTON))

    private fun circleButton(
        @DrawableRes icon: Int,
        @StringRes description: Int,
        onClick: () -> Unit,
    ): ImageView =
        ImageView(context).apply {
            setImageDrawable(parts.tinted(icon, theme.stripIcon))
            setPadding(dp(BUTTON_PADDING), dp(BUTTON_PADDING), dp(BUTTON_PADDING), dp(BUTTON_PADDING))
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.keyColor(KeyStyle.LETTER, false))
                }
            contentDescription = context.getString(description)
            setOnClickListener { onClick() }
        }

    private fun updateButtons() {
        parts.select(clipboardTab, !showingResults)
        parts.select(resultsTab, showingResults)
        // The prompt results are switched on and off in the settings, not here.
        toggleButton.visibility = if (showingResults) INVISIBLE else VISIBLE
        val on = prefs.keepHistory
        toggleButton.setImageDrawable(
            parts.tinted(if (on) R.drawable.ic_toggle_on else R.drawable.ic_toggle_off, theme.stripIcon),
        )
        toggleButton.contentDescription =
            context.getString(if (on) R.string.clip_history_on else R.string.clip_history_off)
        editButton.setImageDrawable(
            parts.tinted(if (editing) R.drawable.ic_check else R.drawable.ic_edit, theme.stripIcon),
        )
        editButton.contentDescription = context.getString(if (editing) R.string.clip_done else R.string.clip_edit)
        val editable = if (showingResults) results?.history?.entries?.isNotEmpty() == true else on
        editButton.visibility = if (editable) VISIBLE else INVISIBLE
    }

    private fun toggleHistory() {
        prefs.keepHistory = !prefs.keepHistory
        editing = false
        refresh()
    }

    private fun toggleEditing() {
        editing = !editing
        refresh()
    }

    private fun openTab(results: Boolean) {
        if (showingResults == results) return
        showingResults = results
        editing = false
        refresh()
    }

    // endregion

    // region Cards

    private fun filterRow(filters: List<PromptFilter>): View {
        val row = LinearLayout(context).apply { setPadding(dp(SIDE), dp(GAP / 2), dp(SIDE), dp(GAP)) }
        for ((index, option) in filters.withIndex()) {
            val params = LayoutParams(LayoutParams.WRAP_CONTENT, dp(CHIP_HEIGHT))
            if (index > 0) params.marginStart = dp(GAP / 2)
            val chip =
                parts.chip(filterTitle(option), selected = option == filter) {
                    filter = option
                    refresh()
                }
            row.addView(chip, params)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(row)
        }
    }

    private fun filterTitle(option: PromptFilter): String =
        when (option) {
            PromptFilter.All -> context.getString(R.string.prompt_results_all)
            PromptFilter.Transcripts -> context.getString(R.string.prompt_results_transcript)
            is PromptFilter.Prompt -> option.title
        }

    private fun resultCard(result: PromptResult): View {
        val caption =
            when (result.kind) {
                PromptResult.Kind.TRANSCRIPT -> context.getString(R.string.prompt_results_transcript)
                PromptResult.Kind.ANSWER -> result.prompts.joinToString(", ") { it.title }
            }
        return parts.card(textBody(result.text, caption), editing, { results?.remove(result.id) }) {
            if (editing) {
                results?.remove(result.id)
            } else {
                listener?.onClipboardPick(ClipEntry(result.id, ClipPayload.Text(result.text)))
            }
        }
    }

    private fun card(entry: ClipEntry): View {
        val body =
            when (val payload = entry.payload) {
                is ClipPayload.Text -> textBody(payload.text)
                is ClipPayload.Image -> imageBody(payload)
            }
        return parts.card(body, editing, { store.remove(entry.id) }) {
            if (editing) store.remove(entry.id) else listener?.onClipboardPick(entry)
        }
    }

    /** The text of a card; a [caption] says where a prompt result came from. */
    private fun textBody(
        text: String,
        caption: String? = null,
    ): View {
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        if (!caption.isNullOrEmpty()) {
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(GAP / 2)
            column.addView(parts.caption(caption), params)
        }
        // A prompt result is shown as text even when it is a single link.
        val single =
            if (caption ==
                null
            ) {
                ClipExtractor.extract(text).singleOrNull()?.takeIf { it.value == text.trim() }
            } else {
                null
            }
        if (single != null) {
            val icon =
                when (single.kind) {
                    ClipKind.LINK -> R.drawable.ic_link
                    ClipKind.EMAIL -> R.drawable.ic_mail
                    else -> R.drawable.ic_phone
                }
            column.addView(
                ImageView(context).apply { setImageDrawable(parts.tinted(icon, theme.suggestion)) },
                LayoutParams(dp(ICON), dp(ICON)),
            )
        }
        column.addView(
            TextView(context).apply {
                this.text = text.trim()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SIZE)
                setTextColor(theme.suggestion)
                maxLines = MAX_LINES
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        column.setPadding(dp(CARD_PADDING), dp(CARD_PADDING), dp(CARD_PADDING), dp(CARD_PADDING))
        return column
    }

    private fun imageBody(image: ClipPayload.Image): View =
        ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = dp(IMAGE_MIN_HEIGHT)
            val uri = Uri.fromFile(store.imageFile(image.source))
            decoder.execute {
                val bitmap = Thumbnails.load(context, uri, THUMBNAIL_SIDE)
                if (bitmap != null) post { setImageBitmap(bitmap) }
            }
        }

    private fun estimatedHeight(entry: ClipEntry): Float =
        when (val payload = entry.payload) {
            is ClipPayload.Image -> IMAGE_ESTIMATE
            is ClipPayload.Text -> estimatedTextHeight(payload.text)
        }

    private fun estimatedTextHeight(text: String): Float =
        TEXT_BASE + TEXT_PER_LINE * minOf(MAX_LINES, text.length / CHARS_PER_LINE + 1)

    // endregion

    private fun dp(value: Int) = parts.dp(value)

    private companion object {
        const val HEADER_HEIGHT = 56
        const val BUTTON = 44
        const val BUTTON_PADDING = 10
        const val ICON = 24
        const val TITLE_SIZE = 22f
        const val CARD_TEXT_SIZE = 16f
        const val CARD_PADDING = 14
        const val MAX_LINES = 6
        const val IMAGE_MIN_HEIGHT = 96
        const val THUMBNAIL_SIDE = 480
        const val IMAGE_ESTIMATE = 160f
        const val TEXT_BASE = 28f
        const val TEXT_PER_LINE = 22f
        const val CHARS_PER_LINE = 22
        const val TAB_HEIGHT = 36
        const val CHIP_HEIGHT = 32
        const val CAPTION_HEIGHT = 18f
        const val HANDLE = 40
        const val LIFT = 8
        const val SCROLL_EDGE = 48
    }
}
