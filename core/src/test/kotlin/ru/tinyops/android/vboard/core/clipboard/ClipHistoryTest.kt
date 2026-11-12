package ru.tinyops.android.vboard.core.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals

class ClipHistoryTest {
    private fun text(
        id: Long,
        value: String,
    ) = ClipEntry(id, ClipPayload.Text(value))

    private fun image(
        id: Long,
        file: String,
    ) = ClipEntry(id, ClipPayload.Image(file, "image/png"))

    @Test
    fun `newest entry goes first`() {
        val history =
            ClipHistory()
                .add(text(1, "a"))
                .history
                .add(text(2, "b"))
                .history
        assertEquals(listOf(2L, 1L), history.entries.map { it.id })
    }

    @Test
    fun `copying the same text again moves it to the top without a duplicate`() {
        val history =
            ClipHistory()
                .add(text(1, "a"))
                .history
                .add(text(2, "b"))
                .history
                .add(text(3, "a"))
                .history
        assertEquals(listOf("a", "b"), history.entries.map { (it.payload as ClipPayload.Text).text })
        assertEquals(3L, history.entries.first().id)
    }

    @Test
    fun `oldest entries are dropped over capacity and reported`() {
        var history = ClipHistory(capacity = 2)
        history = history.add(image(1, "one.png")).history
        history = history.add(text(2, "b")).history
        val change = history.add(text(3, "c"))
        assertEquals(listOf(3L, 2L), change.history.entries.map { it.id })
        assertEquals(listOf(1L), change.dropped.map { it.id })
    }

    @Test
    fun `replaced duplicate is not reported as dropped`() {
        val history = ClipHistory().add(image(1, "same.png")).history
        val change = history.add(image(2, "same.png"))
        assertEquals(1, change.history.entries.size)
        assertEquals(emptyList(), change.dropped)
    }

    @Test
    fun `remove and clear report what left`() {
        val history =
            ClipHistory()
                .add(text(1, "a"))
                .history
                .add(text(2, "b"))
                .history
        assertEquals(
            listOf(1L),
            history
                .remove(2)
                .history.entries
                .map { it.id },
        )
        assertEquals(listOf(2L), history.remove(2).dropped.map { it.id })
        assertEquals(2, history.clear().dropped.size)
        assertEquals(emptyList(), history.clear().history.entries)
    }
}
