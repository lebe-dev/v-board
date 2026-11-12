package ru.tinyops.android.vboard.ui

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlternatesPopupTest {
    @Test
    fun `up to five alternates stay in one row`() {
        assertEquals(PopupGrid(columns = 1, rows = 1), PopupGrid.of(1))
        assertEquals(PopupGrid(columns = 5, rows = 1), PopupGrid.of(5))
    }

    @Test
    fun `longer lists are split into two rows`() {
        assertEquals(PopupGrid(columns = 3, rows = 2), PopupGrid.of(6))
        assertEquals(PopupGrid(columns = 4, rows = 2), PopupGrid.of(7))
        assertEquals(PopupGrid(columns = 5, rows = 2), PopupGrid.of(10))
    }

    @Test
    fun `the first alternate is in the bottom row above the key`() {
        val popup = popup(count = 7)
        val first = popup.cell(0)
        val fifth = popup.cell(4)
        assertEquals(popup.bounds.left, first.left, 0.01f)
        assertEquals(popup.bounds.bottom, first.bottom, 0.01f)
        assertEquals(popup.bounds.left, fifth.left, 0.01f)
        assertEquals(popup.bounds.bottom - 20f, fifth.bottom, 0.01f)
    }

    @Test
    fun `a finger picks the cell under it`() {
        val popup = popup(count = 7)
        assertEquals(0, popup.indexAt(105f, 95f))
        assertEquals(3, popup.indexAt(135f, 95f))
        assertEquals(4, popup.indexAt(105f, 75f))
        assertEquals(6, popup.indexAt(125f, 75f))
    }

    @Test
    fun `a finger outside the popup is clamped to the nearest cell`() {
        val popup = popup(count = 7)
        assertEquals(0, popup.indexAt(0f, 500f))
        assertEquals(3, popup.indexAt(900f, 500f))
        assertEquals(6, popup.indexAt(900f, -500f))
        assertEquals(6, popup.indexAt(135f, 75f))
    }

    // Four columns of 10 by 20, two rows, with the bottom edge at y = 100.
    private fun popup(count: Int): AlternatesPopup {
        val grid = PopupGrid.of(count)
        return AlternatesPopup(
            pointerId = 0,
            alternates = List(count) { "$it" },
            bounds = RectF(100f, 100f - 20f * grid.rows, 100f + 10f * grid.columns, 100f),
            grid = grid,
            cellWidth = 10f,
            cellHeight = 20f,
        )
    }
}
