package ru.tinyops.android.vboard.ui

import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonPlaces

/**
 * Where the custom, clipboard and fullscreen buttons of the strip stand: how wide they are, how many of
 * them the strip holds and which go to the second row under it. The places are worked out again only when
 * the buttons or the room change.
 *
 * @param rowHeight the height of the strip, and of the second row
 * @param minSize the narrowest a button is made, so that neighbouring icons do not touch
 */
internal class StripButtonRows(
    private val rowHeight: Int,
    private val minSize: Float,
) {
    /** The width of a button as a share of the strip's height. */
    var widthScale: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SCALE, 1f)
        }

    /** Whether the buttons that do not fit on the strip go to a second row under it. */
    var secondRow: Boolean = false

    /** How many custom buttons the field may show, with text in it or without. */
    var planned: Int = 0

    val size: Float get() = maxOf(rowHeight * widthScale, minSize)

    /** The buttons that stand on the strip, then those of the second row. */
    class Arrangement(
        val buttons: List<StripButton>,
        val firstRow: Int,
    )

    private var arrangement: Arrangement? = null
    private var arrangedOf: List<StripButton>? = null
    private var arrangedCapacity = -1

    /**
     * The first [capacity] of the [offered] buttons fit on the strip and go away while they are hidden; the rest
     * stand in the second row all the same.
     */
    fun arrange(
        offered: List<StripButton>,
        capacity: Int,
    ): Arrangement {
        val cached = arrangement
        if (cached != null && arrangedOf === offered && arrangedCapacity == capacity) return cached
        val onStripCount = if (secondRow) offered.stripCountOf(capacity) else capacity
        val onStrip = offered.take(onStripCount).filter { !it.hidden }
        return Arrangement(onStrip + offered.drop(onStripCount), onStrip.size).also {
            arrangement = it
            arrangedOf = offered
            arrangedCapacity = capacity
        }
    }

    private fun List<StripButton>.stripCountOf(capacity: Int): Int {
        val pinned = indexOfFirst { it.secondRow }
        return minOf(capacity, if (pinned < 0) size else pinned)
    }

    private var stripCache: ButtonPlaces? = null
    private var stripOf: List<ButtonPlacement>? = null
    private var belowCache: ButtonPlaces? = null
    private var belowOf: List<ButtonPlacement>? = null

    /** How many of [count] custom buttons fit between [start] and [end] after [fixed] buttons took their places. */
    fun fit(
        start: Float,
        end: Float,
        count: Int,
        fixed: Int,
    ): Int = minOf(count, (((end - start) / size).toInt() - fixed).coerceAtLeast(0))

    /**
     * One row or two for a strip [width] wide: two when the second row is on and the custom buttons might not
     * fit on the strip. The room is counted with the proofread button in its place, as it comes and goes with
     * the text of the field, and the keyboard should not change its height while typing. A button the user put
     * in the second row asks for it too.
     */
    fun rows(
        width: Float,
        shown: Int,
        fixed: Int,
        pinned: Boolean = false,
    ): Int {
        if (!secondRow) return 1
        if (pinned) return 2
        val wanted = maxOf(planned, shown)
        return if (fit(0f, width - FIXED_ON_STRIP * rowHeight, wanted, fixed) < wanted) 2 else 1
    }

    /** The places on the strip between [start] and [end]. */
    fun onStrip(
        placements: List<ButtonPlacement>,
        start: Float,
        end: Float,
    ): ButtonPlaces {
        val cached = stripCache
        if (cached != null && stripOf == placements && cached.same(start, end)) return cached
        return ButtonPlaces.of(placements, start, end, size).also {
            stripCache = it
            stripOf = placements
        }
    }

    /** The places in the second row, which takes the whole [width]. */
    fun below(
        placements: List<ButtonPlacement>,
        width: Float,
    ): ButtonPlaces {
        val cached = belowCache
        if (cached != null && belowOf == placements && cached.same(0f, width)) return cached
        return ButtonPlaces.of(placements, 0f, width, size).also {
            belowCache = it
            belowOf = placements
        }
    }

    private fun ButtonPlaces.same(
        start: Float,
        end: Float,
    ) = this.start == start && this.end == end && this.size == this@StripButtonRows.size

    private companion object {
        /** The narrowest a button is made, as a share of the strip's height. */
        const val MIN_SCALE = 0.6f

        /** The cog, the proofread button and the microphone, each as wide as the strip is high. */
        const val FIXED_ON_STRIP = 3
    }
}
