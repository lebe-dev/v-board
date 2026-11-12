package ru.tinyops.android.vboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tinyops.android.vboard.core.voice.ButtonVisibility
import ru.tinyops.android.vboard.core.voice.CustomButton

class ButtonSectionsTest {
    private val keyboard = CustomButton("k")
    private val always = CustomButton("a", visibility = ButtonVisibility.ALWAYS)
    private val panel = CustomButton("p", visibility = ButtonVisibility.FULLSCREEN)

    @Test
    fun `buttons go to the section of the row they stand in`() {
        val sections = ButtonSections(listOf(panel, keyboard, always), fullscreenEnabled = true)
        assertEquals(listOf(keyboard, always), sections.keyboard)
        assertEquals(listOf(panel), sections.panel)
    }

    @Test
    fun `with the mode on its section is there even without buttons`() {
        val sections = ButtonSections(listOf(keyboard), fullscreenEnabled = true)
        assertTrue(sections.panelSectionShown)
        assertTrue(sections.panelCanAdd)
        assertFalse(sections.panelPreviewShown)
        assertFalse(sections.panelOffNote)
    }

    @Test
    fun `with the mode on the buttons get a model of the panel`() {
        val sections = ButtonSections(listOf(panel), fullscreenEnabled = true)
        assertTrue(sections.panelPreviewShown)
    }

    @Test
    fun `with the mode off and no buttons for it the section is gone`() {
        val sections = ButtonSections(listOf(keyboard), fullscreenEnabled = false)
        assertFalse(sections.panelSectionShown)
    }

    @Test
    fun `with the mode off its buttons stay reachable and nothing can be arranged or added`() {
        val sections = ButtonSections(listOf(panel), fullscreenEnabled = false)
        assertTrue(sections.panelSectionShown)
        assertTrue(sections.panelOffNote)
        assertFalse(sections.panelPreviewShown)
        assertFalse(sections.panelCanAdd)
    }

    @Test
    fun `turned off buttons stay in the lists but not on the models`() {
        val off = keyboard.copy(id = "off", enabled = false)
        val panelOff = panel.copy(id = "panel off", enabled = false)
        val sections = ButtonSections(listOf(keyboard, off, panelOff), fullscreenEnabled = true)
        assertEquals(listOf(keyboard, off), sections.keyboard)
        assertEquals(listOf(keyboard), sections.keyboardOnModel)
        assertEquals(listOf(panelOff), sections.panel)
        assertFalse(sections.panelPreviewShown)
    }

    @Test
    fun `each section has a limit of its own`() {
        val full = List(CustomButton.MAX_PER_SECTION) { CustomButton("p$it", visibility = ButtonVisibility.FULLSCREEN) }
        val sections = ButtonSections(full, fullscreenEnabled = true)
        assertFalse(sections.panelCanAdd)
        assertTrue(sections.keyboardCanAdd)
        assertTrue(sections.keyboardSectionShown)
    }

    @Test
    fun `a full keyboard section takes no more buttons and keeps the panel open`() {
        val full = List(CustomButton.MAX_PER_SECTION) { CustomButton("k$it") }
        val sections = ButtonSections(full, fullscreenEnabled = true)
        assertFalse(sections.keyboardCanAdd)
        assertTrue(sections.panelCanAdd)
    }

    @Test
    fun `more than six buttons fit in a section`() {
        val seven = List(7) { CustomButton("k$it") }
        assertTrue(ButtonSections(seven, fullscreenEnabled = false).keyboardCanAdd)
    }

    @Test
    fun `the keyboard section stays while a button can be added to it`() {
        assertTrue(ButtonSections(listOf(panel), fullscreenEnabled = true).keyboardSectionShown)
    }

    @Test
    fun `members are listed under their group and not in the section`() {
        val group = CustomButton("g", group = true)
        val member = CustomButton("m", groupId = "g")
        val sections = ButtonSections(listOf(keyboard, group, member), fullscreenEnabled = true)
        assertEquals(listOf(keyboard, group), sections.keyboard)
        assertEquals(listOf(keyboard, group), sections.keyboardOnModel)
        assertEquals(listOf(member), sections.membersOf("g"))
        assertEquals(listOf(group), sections.groupsOf(panel = false))
    }

    @Test
    fun `members do not count against the room of a section`() {
        val group = CustomButton("g", group = true)
        val members = List(CustomButton.MAX_PER_SECTION) { CustomButton("m$it", groupId = "g") }
        assertTrue(ButtonSections(listOf(group) + members, fullscreenEnabled = false).keyboardCanAdd)
    }
}
