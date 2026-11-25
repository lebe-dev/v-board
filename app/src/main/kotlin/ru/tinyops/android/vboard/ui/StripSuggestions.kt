package ru.tinyops.android.vboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils

/**
 * Lays out and draws up to three suggestion slots on [SuggestionStripView]. Drops from three slots
 * to two when long words overflow, and ellipsizes the start instead of the end when shown words
 * share a long prefix.
 */
internal class StripSuggestions(
    private val theme: KeyboardTheme,
    private val textPaint: TextPaint,
    private val boldPaint: TextPaint,
    private val dividerPaint: Paint,
) {
    data class Slot(
        val zone: SuggestionStripView.Zone,
        val word: String?,
        val primary: Boolean = false,
    )

    fun activeSlots(
        suggestions: List<String>,
        slotsLeft: Float,
        slotsRight: Float,
    ): List<Slot> {
        if (suggestions.isEmpty()) return emptyList()
        val totalRoom = slotsRight - slotsLeft
        val threeRoom = (totalRoom / SLOTS) * TEXT_WIDTH
        val fitsInThree =
            totalRoom <= 0f ||
                suggestions.withIndex().all { (index, word) ->
                    paintFor(primary = index == 0).measureText(word) <= threeRoom
                }
        return when {
            fitsInThree ->
                listOf(
                    Slot(SuggestionStripView.Zone.SLOT_LEFT, suggestions.getOrNull(1)),
                    Slot(SuggestionStripView.Zone.SLOT_MIDDLE, suggestions[0], primary = true),
                    Slot(SuggestionStripView.Zone.SLOT_RIGHT, suggestions.getOrNull(2)),
                )
            suggestions.size == 1 -> listOf(Slot(SuggestionStripView.Zone.SLOT_MIDDLE, suggestions[0], primary = true))
            else ->
                listOf(
                    Slot(SuggestionStripView.Zone.SLOT_LEFT, suggestions[1]),
                    Slot(SuggestionStripView.Zone.SLOT_RIGHT, suggestions[0], primary = true),
                )
        }
    }

    fun zoneAt(
        suggestions: List<String>,
        x: Float,
        slotsLeft: Float,
        slotsRight: Float,
    ): SuggestionStripView.Zone {
        val slots = activeSlots(suggestions, slotsLeft, slotsRight)
        if (slots.isEmpty()) return SuggestionStripView.Zone.NONE
        val slotWidth = slotWidthFor(slots, slotsLeft, slotsRight)
        val index = ((x - slotsLeft) / slotWidth).toInt().coerceIn(0, slots.lastIndex)
        val slot = slots[index]
        return if (slot.word != null) slot.zone else SuggestionStripView.Zone.NONE
    }

    fun boundsOf(
        suggestions: List<String>,
        zone: SuggestionStripView.Zone,
        slotsLeft: Float,
        slotsRight: Float,
    ): Pair<Float, Float>? {
        val slots = activeSlots(suggestions, slotsLeft, slotsRight)
        val index = slots.indexOfFirst { it.zone == zone }
        if (index < 0) return null
        val slotWidth = slotWidthFor(slots, slotsLeft, slotsRight)
        val left = slotsLeft + index * slotWidth
        return left to left + slotWidth
    }

    fun displayed(
        suggestions: List<String>,
        slotsLeft: Float,
        slotsRight: Float,
    ): List<String> {
        val slots = activeSlots(suggestions, slotsLeft, slotsRight)
        if (slots.isEmpty()) return emptyList()
        val maxTextWidth = slotWidthFor(slots, slotsLeft, slotsRight) * TEXT_WIDTH
        val truncateAt = truncateModeFor(slots.mapNotNull { it.word }, maxTextWidth)
        return slots.mapNotNull { slot ->
            val word = slot.word ?: return@mapNotNull null
            TextUtils.ellipsize(word, paintFor(slot.primary), maxTextWidth, truncateAt).toString()
        }
    }

    fun draw(
        canvas: Canvas,
        suggestions: List<String>,
        slotsLeft: Float,
        slotsRight: Float,
        rowHeight: Int,
        autocorrectHighlight: Boolean,
    ) {
        val slots = activeSlots(suggestions, slotsLeft, slotsRight)
        if (slots.isEmpty()) return
        val slotWidth = slotWidthFor(slots, slotsLeft, slotsRight)
        val maxTextWidth = slotWidth * TEXT_WIDTH
        val truncateAt = truncateModeFor(slots.mapNotNull { it.word }, maxTextWidth)
        val baseline = textPaint.baselineIn(rowHeight)
        for ((index, slot) in slots.withIndex()) {
            val word = slot.word ?: continue
            val paint = paintFor(slot.primary)
            if (slot.primary) paint.color = if (autocorrectHighlight) theme.accent else theme.suggestion
            val text = TextUtils.ellipsize(word, paint, maxTextWidth, truncateAt)
            val center = slotsLeft + index * slotWidth + slotWidth / 2f
            canvas.drawText(text, 0, text.length, center, baseline, paint)
        }
        val dividerHeight = rowHeight * DIVIDER_HEIGHT
        for (slot in 1 until slots.size) {
            val x = slotsLeft + slot * slotWidth
            canvas.drawRect(x, (rowHeight - dividerHeight) / 2f, x + 1f, (rowHeight + dividerHeight) / 2f, dividerPaint)
        }
    }

    private fun paintFor(primary: Boolean): TextPaint = if (primary) boldPaint else textPaint

    private fun slotWidthFor(
        slots: List<Slot>,
        slotsLeft: Float,
        slotsRight: Float,
    ): Float = (slotsRight - slotsLeft) / slots.size.coerceAtLeast(1)

    private fun truncateModeFor(
        words: List<String>,
        maxTextWidth: Float,
    ): TextUtils.TruncateAt {
        if (words.size < 2 || maxTextWidth <= 0f) return TextUtils.TruncateAt.END
        val prefixLen = commonPrefixLength(words)
        if (prefixLen < MIN_SHARED_PREFIX) return TextUtils.TruncateAt.END
        val prefixWidth = textPaint.measureText(words[0], 0, prefixLen)
        return if (prefixWidth >= maxTextWidth * SHARED_PREFIX_RATIO) {
            TextUtils.TruncateAt.START
        } else {
            TextUtils.TruncateAt.END
        }
    }

    private companion object {
        const val SLOTS = 3
        const val MIN_SHARED_PREFIX = 3
        const val SHARED_PREFIX_RATIO = 0.5f
        const val TEXT_WIDTH = 0.9f
        const val DIVIDER_HEIGHT = 0.4f
    }
}

private fun commonPrefixLength(words: List<String>): Int {
    val first = words.firstOrNull() ?: return 0
    var len = first.length
    for (i in 1 until words.size) {
        val other = words[i]
        val limit = minOf(len, other.length)
        var matched = 0
        while (matched < limit && first[matched].lowercaseChar() == other[matched].lowercaseChar()) {
            matched++
        }
        len = matched
        if (len == 0) return 0
    }
    return len
}
