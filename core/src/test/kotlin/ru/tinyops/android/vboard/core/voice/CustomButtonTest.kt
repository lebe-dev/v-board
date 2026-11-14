package ru.tinyops.android.vboard.core.voice

import ru.tinyops.android.vboard.core.input.CursorWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomButtonTest {
    private fun window(
        text: String,
        start: Int = 0,
        end: Int = start,
        offset: Int = 0,
    ) = CursorWindow(text, start, end, offset)

    @Test
    fun `replace all sends the whole text even with a selection and takes the whole field`() {
        val request = ButtonRequest.of(window(" раз два ", 1, 4), InsertMode.REPLACE_ALL)
        assertEquals(ButtonRequest("раз два", 0, 9), request)
    }

    @Test
    fun `replace selection sends the selection and takes only it`() {
        val request = ButtonRequest.of(window("раз два", 4, 7), InsertMode.REPLACE_SELECTION)
        assertEquals(ButtonRequest("два", 4, 7), request)
    }

    @Test
    fun `replace selection without one takes the whole text`() {
        val request = ButtonRequest.of(window("раз два", 2), InsertMode.REPLACE_SELECTION)
        assertEquals(ButtonRequest("раз два", 0, 7), request)
    }

    @Test
    fun `append inserts at the end after a space`() {
        val request = ButtonRequest.of(window("раз два", 2), InsertMode.APPEND)
        assertEquals(ButtonRequest("раз два", 7, 7, separated = true), request)
        assertEquals(" ответ", request?.replacement("ответ"))
    }

    @Test
    fun `append adds no space after whitespace`() {
        val request = ButtonRequest.of(window("раз\n", 0), InsertMode.APPEND)
        assertEquals(ButtonRequest("раз", 4, 4, separated = false), request)
        assertEquals("ответ", request?.replacement("ответ"))
    }

    @Test
    fun `at cursor inserts after the selection and sends it`() {
        val request = ButtonRequest.of(window("раз два", 0, 3), InsertMode.AT_CURSOR)
        assertEquals(ButtonRequest("раз", 3, 3, separated = true), request)
    }

    @Test
    fun `at the start of the field no space is needed`() {
        val request = ButtonRequest.of(window("раз", 0), InsertMode.AT_CURSOR)
        assertEquals(ButtonRequest("раз", 0, 0, separated = false), request)
    }

    @Test
    fun `positions are those of the editor`() {
        val request = ButtonRequest.of(window("два три", 4, 7, offset = 100), InsertMode.AT_CURSOR)
        assertEquals(ButtonRequest("три", 107, 107, separated = true), request)
    }

    @Test
    fun `a blank field and an editor without offsets give no request`() {
        assertNull(ButtonRequest.of(window("  "), InsertMode.APPEND))
        assertNull(ButtonRequest.of(window("раз", offset = -1), InsertMode.AT_CURSOR))
    }

    @Test
    fun `a selection frees only the modes that need nothing else from the text`() {
        val long = window("a".repeat(30), 0, 3)
        assertFalse(ButtonRequest.tooLong(long, InsertMode.REPLACE_SELECTION, 10))
        assertFalse(ButtonRequest.tooLong(long, InsertMode.AT_CURSOR, 10))
        assertTrue(ButtonRequest.tooLong(long, InsertMode.REPLACE_ALL, 10))
        assertTrue(ButtonRequest.tooLong(long, InsertMode.APPEND, 10))
    }

    @Test
    fun `buttons are replaced in place`() {
        val buttons = listOf(CustomButton("a"), CustomButton("b"), CustomButton("c"))
        val changed = buttons.withButton(CustomButton("b", ButtonCommand(ButtonAction.COPY)))
        assertEquals(ButtonAction.COPY, changed[1].main?.action)
    }

    @Test
    fun `a fullscreen button is never on the strip, with text or without`() {
        val button = CustomButton("a", visibility = ButtonVisibility.FULLSCREEN)
        assertTrue(button.fullscreenOnly)
        assertFalse(button.shownFor(fieldHasText = true))
        assertFalse(button.shownFor(fieldHasText = false))
        assertEquals(ButtonVisibility.FULLSCREEN, ButtonVisibility.fromId("fullscreen"))
    }

    @Test
    fun `the other buttons keep their visibility rules`() {
        assertFalse(CustomButton("a").fullscreenOnly)
        assertTrue(CustomButton("a").shownFor(fieldHasText = true))
        assertFalse(CustomButton("a").shownFor(fieldHasText = false))
        assertTrue(CustomButton("a", visibility = ButtonVisibility.ALWAYS).shownFor(fieldHasText = false))
    }

    @Test
    fun `arranging a part of the list leaves the other buttons where they stand`() {
        val panel = ButtonVisibility.FULLSCREEN
        val a = CustomButton("a")
        val b = CustomButton("b", visibility = panel)
        val c = CustomButton("c")
        val d = CustomButton("d", visibility = panel)
        val movedD = d.copy(placement = ButtonPlacement.RIGHT)
        assertEquals(listOf(a, movedD, c, b), listOf(a, b, c, d).withArrangedPart(listOf(movedD, b)))
    }

    private val left = ButtonPlacement.LEFT
    private val center = ButtonPlacement.CENTER
    private val right = ButtonPlacement.RIGHT

    @Test
    fun `buttons stand by their places in the order of the list`() {
        val places = ButtonPlaces.of(listOf(right, left, center, left), 0f, 100f, 10f)
        assertEquals(listOf(90f, 0f, 45f, 10f), places.lefts.toList())
    }

    @Test
    fun `what does not fit is left out, first in the list stays`() {
        val places = ButtonPlaces.of(listOf(left, right, center, left), 0f, 25f, 10f)
        assertEquals(listOf(0f, 15f), places.lefts.toList())
    }

    @Test
    fun `center buttons move aside for the others`() {
        val places = ButtonPlaces.of(listOf(left, left, left, center, center), 0f, 60f, 10f)
        assertEquals(listOf(0f, 10f, 20f, 30f, 40f), places.lefts.toList())
    }

    @Test
    fun `the gap is the widest stretch between the groups`() {
        val free = ButtonPlaces.of(listOf(left, center), 0f, 100f, 10f)
        assertEquals(55f, free.gapStart)
        assertEquals(100f, free.gapEnd)
        val around = ButtonPlaces.of(listOf(left, right), 0f, 100f, 10f)
        assertEquals(10f, around.gapStart)
        assertEquals(90f, around.gapEnd)
        val empty = ButtonPlaces.of(emptyList(), 0f, 100f, 10f)
        assertEquals(0f, empty.gapStart)
        assertEquals(100f, empty.gapEnd)
    }

    @Test
    fun `a position is found under its button only`() {
        val places = ButtonPlaces.of(listOf(left, right), 0f, 100f, 10f)
        assertEquals(0, places.indexAt(5f))
        assertEquals(1, places.indexAt(95f))
        assertEquals(-1, places.indexAt(50f))
    }

    private val row = listOf(CustomButton("a"), CustomButton("b"), CustomButton("c"))

    private fun dropped(
        id: String,
        center: Float,
        list: List<CustomButton> = row,
    ) = list.withButtonDropped(id, center, 0f, 100f, 10f)

    @Test
    fun `a button dropped where it stands changes nothing`() {
        assertEquals(row, dropped("b", 15f))
    }

    @Test
    fun `a button dragged past its neighbour takes its place`() {
        assertEquals(listOf("b", "a", "c"), dropped("a", 15f).map { it.id })
        assertEquals(listOf("c", "a", "b"), dropped("c", 5f).map { it.id })
    }

    @Test
    fun `a button dragged to another part of the strip goes there`() {
        val middle = dropped("a", 50f).first { it.id == "a" }
        assertEquals(ButtonPlacement.CENTER, middle.placement)
        val far = dropped("a", 95f).first { it.id == "a" }
        assertEquals(ButtonPlacement.RIGHT, far.placement)
    }

    @Test
    fun `within a part the button goes between the others`() {
        val list = listOf(CustomButton("a"), CustomButton("b", placement = right), CustomButton("c", placement = right))
        val result = dropped("a", 85f, list)
        assertEquals(listOf("b", "a", "c"), result.map { it.id })
        assertEquals(right, result[1].placement)
    }

    @Test
    fun `a button from beyond the room comes in and pushes the last one out`() {
        val list = ('a'..'e').map { CustomButton(it.toString()) }
        val result = list.withButtonDropped("e", 5f, 0f, 30f, 10f)
        assertEquals(listOf("e", "a", "b", "c", "d"), result.map { it.id })
    }

    @Test
    fun `what stands after the buttons takes a place of its own`() {
        val list = ('a'..'d').map { CustomButton(it.toString()) }
        val result = list.withButtonDropped("d", 5f, 0f, 40f, 10f, last = listOf(right))
        assertEquals(listOf("d", "a", "b", "c"), result.map { it.id })
        val kept = list.withButtonDropped("a", 35f, 0f, 40f, 10f, last = listOf(right))
        val places = ButtonPlaces.of(kept.take(3).map { it.placement } + right, 0f, 40f, 10f)
        assertEquals(20f, places.lefts[kept.indexOfFirst { it.id == "a" }])
    }

    @Test
    fun `a button goes last to leave the strip`() {
        assertEquals(listOf("b", "c", "a"), row.withButtonLast("a").map { it.id })
    }

    @Test
    fun `a button dragged down from the strip joins the second row where it is dropped`() {
        val list = ('a'..'e').map { CustomButton(it.toString()) }
        // The strip holds a and b; the second row holds c, d and e from x = 0.
        val result = list.withButtonDroppedBelow("a", 15f, 0f, 100f, 10f, above = 2)
        assertEquals(listOf("b", "c", "d", "a", "e"), result.map { it.id })
    }

    @Test
    fun `a button dragged down joins the second row although the strip has room`() {
        val list = ('a'..'c').map { CustomButton(it.toString()) }
        val result = list.withButtonDroppedBelow("a", 5f, 0f, 100f, 10f, above = 2)
        assertEquals(listOf("b", "c", "a"), result.map { it.id })
        assertTrue(result.last().secondRow)
        assertEquals(2, result.stripCount(fit = 8))
    }

    @Test
    fun `a button dragged back to the strip leaves the second row`() {
        val list =
            listOf(CustomButton("a"), CustomButton("b", secondRow = true), CustomButton("c", secondRow = true))
        val result = list.withButtonDroppedOnStrip("c", 5f, 0f, 100f, 10f)
        assertEquals(listOf("c", "a", "b"), result.map { it.id })
        assertEquals(listOf(false, false, true), result.map { it.secondRow })
        assertEquals(2, result.stripCount(fit = 8))
    }

    @Test
    fun `the strip ends where the second row begins or where the room does`() {
        val list = listOf(CustomButton("a"), CustomButton("b", secondRow = true), CustomButton("c"))
        assertEquals(1, list.stripCount(fit = 5))
        assertEquals(1, list.stripCount(fit = 1))
        assertEquals(0, list.stripCount(fit = 0))
        assertEquals(3, ('a'..'c').map { CustomButton(it.toString()) }.stripCount(fit = 5))
    }

    @Test
    fun `a button moves within the second row and the strip stays as it was`() {
        val list = ('a'..'e').map { CustomButton(it.toString()) }
        val result = list.withButtonDroppedBelow("e", 5f, 0f, 100f, 10f, above = 2)
        assertEquals(listOf("a", "b", "e", "c", "d"), result.map { it.id })
        val far = list.withButtonDroppedBelow("c", 95f, 0f, 100f, 10f, above = 2).first { it.id == "c" }
        assertEquals(right, far.placement)
    }

    @Test
    fun `every color stands out on the keyboard it is meant for`() {
        ButtonColor.entries.filter { it != ButtonColor.THEME }.forEach {
            assertTrue(contrast(it.tint(dark = false, accent = 0), 0xFFFFFFFF.toInt()) >= MIN_CONTRAST, "$it on light")
            assertTrue(contrast(it.tint(dark = true, accent = 0), 0xFF202124.toInt()) >= MIN_CONTRAST, "$it on dark")
        }
    }

    @Test
    fun `the theme color is the accent of the keyboard theme on either background`() {
        val accent = 0xFF3B63E0.toInt()
        assertEquals(accent, ButtonColor.THEME.tint(dark = false, accent = accent))
        assertEquals(accent, ButtonColor.THEME.tint(dark = true, accent = accent))
        assertEquals(ButtonColor.THEME, ButtonColor.fromId("theme"))
    }

    private fun contrast(
        first: Int,
        second: Int,
    ): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = (color shr shift and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        const val MIN_CONTRAST = 3.0
    }

    private val group = CustomButton("g", group = true)
    private val member = CustomButton("m", groupId = "g")
    private val alone = CustomButton("a")

    @Test
    fun `members stand in their group and not among the top level buttons`() {
        val all = listOf(alone, group, member)
        assertEquals(listOf(alone, group), all.topLevel())
        assertEquals(listOf(member), all.membersOf("g"))
    }

    @Test
    fun `a member of a group that is gone counts as a top level button`() {
        val orphan = CustomButton("o", groupId = "gone")
        assertEquals(listOf(orphan), listOf(orphan).topLevel())
        assertTrue(listOf(orphan).membersOf("g").isEmpty())
    }

    @Test
    fun `a group never is a member of another group`() {
        val nested = CustomButton("n", group = true, groupId = "g")
        assertEquals(listOf(group, nested), listOf(group, nested).topLevel())
        assertTrue(listOf(group, nested).membersOf("g").isEmpty())
    }

    @Test
    fun `deleting a group takes its members along`() {
        assertEquals(listOf(alone), listOf(alone, group, member).withoutButton("g"))
        assertEquals(listOf(alone, group), listOf(alone, group, member).withoutButton("m"))
    }
}
