package ru.tinyops.android.vboard.core.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals

class ClipChipsTest {
    private val message = "Когда мне нужно было обработать массив, я арендовал виртуалку на vast.ai, в итоге быстро."

    @Test
    fun `text chip comes first, extracted links follow`() {
        val chips = ClipChips.of(ClipPayload.Text(message), showExtracted = true)
        assertEquals(listOf(ClipKind.TEXT, ClipKind.LINK), chips.map { it.kind })
        assertEquals(message, chips[0].insert)
        assertEquals("vast.ai", chips[1].label)
        assertEquals("vast.ai", chips[1].insert)
    }

    @Test
    fun `extracted chips can be turned off`() {
        val chips = ClipChips.of(ClipPayload.Text(message), showExtracted = false)
        assertEquals(listOf(ClipKind.TEXT), chips.map { it.kind })
    }

    @Test
    fun `a copied link alone is a single link chip`() {
        val chips = ClipChips.of(ClipPayload.Text("  https://vast.ai/docs\n"), showExtracted = true)
        assertEquals(listOf(ClipKind.LINK), chips.map { it.kind })
        assertEquals("vast.ai/docs", chips[0].label)
        assertEquals("  https://vast.ai/docs\n", chips[0].insert)
    }

    @Test
    fun `label collapses whitespace`() {
        val chips = ClipChips.of(ClipPayload.Text("one\n\n  two\tthree"), showExtracted = true)
        assertEquals("one two three", chips[0].label)
    }

    @Test
    fun `at most three chips are shown`() {
        val text = "тут a.io и b.io и c.io и d.io"
        assertEquals(3, ClipChips.of(ClipPayload.Text(text), showExtracted = true).size)
    }

    @Test
    fun `image is a single image chip`() {
        val chips = ClipChips.of(ClipPayload.Image("content://x/1", "image/png"), showExtracted = true)
        assertEquals(listOf(ClipKind.IMAGE), chips.map { it.kind })
    }

    @Test
    fun `blank text has no chips`() {
        assertEquals(emptyList(), ClipChips.of(ClipPayload.Text("  \n "), showExtracted = true))
    }

    @Test
    fun `a text from another device is one chip with its preview`() {
        val chip = ClipChips.of(RemoteClip("id1", image = false, "привет", "MacBook", 0L))
        assertEquals(ClipChip(ClipKind.TEXT, "привет", insert = null, remote = "id1"), chip)
    }

    @Test
    fun `an image from another device is labelled with the device`() {
        val chip = ClipChips.of(RemoteClip("id2", image = true, "", "MacBook", 0L))
        assertEquals(ClipChip(ClipKind.IMAGE, "MacBook", insert = null, remote = "id2"), chip)
    }
}
