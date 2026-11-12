package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceDictionaryTest {
    private fun apply(
        raw: String,
        text: String,
    ) = VoiceDictionary.parse(raw).apply(text)

    @Test
    fun `replaces whole words ignoring case`() {
        assertEquals("Напиши в Gmail.", apply("джимейл=Gmail", "Напиши в Джимейл."))
        assertEquals("джимейлы", apply("джимейл=Gmail", "джимейлы"))
    }

    @Test
    fun `several spellings share one replacement`() {
        val raw = "джимэйл, джимейл, гмайл=Gmail"
        assertEquals("Gmail и Gmail", apply(raw, "гмайл и джимэйл"))
        assertEquals(3, VoiceDictionary.parse(raw).size)
    }

    @Test
    fun `arrow separator and comments are understood`() {
        val raw = "# термины\nкубер → Kubernetes\n\nарго=Argo CD"
        assertEquals("Kubernetes и Argo CD", apply(raw, "кубер и арго"))
        assertEquals(
            listOf(VoiceDictionary.Entry("кубер", "Kubernetes"), VoiceDictionary.Entry("арго", "Argo CD")),
            VoiceDictionary.entries(raw),
        )
    }

    @Test
    fun `a line without a replacement removes the word`() {
        assertEquals("ну пошли", apply("короче", "ну короче пошли"))
    }

    @Test
    fun `line break command swallows the punctuation and spaces around it`() {
        val raw = "новая строка=\\n"
        assertEquals("Привет\nКак дела?", apply(raw, "Привет новая строка. Как дела?"))
        assertEquals("Список:\n\tпервое", apply("новая строка=\\n\nотступ=\\t", "Список: новая строка отступ первое"))
    }

    @Test
    fun `backslashes inside ordinary text stay literal`() {
        assertEquals("C:\\new", apply("диск=C:\\new", "диск"))
    }

    @Test
    fun `yo rule replaces the letter inside words`() {
        assertEquals("Еще елка", apply("ё=е", "Ещё ёлка"))
    }

    @Test
    fun `rules apply in order and repeated spaces collapse`() {
        val raw = "джава скрипт=JavaScript\nджава=Java"
        assertEquals("JavaScript и Java", apply(raw, "джава скрипт  и джава"))
    }

    @Test
    fun `unusable lines are skipped`() {
        val raw = "a=b → c\nок=OK"
        assertEquals("OK", apply(raw, "ок"))
        assertEquals(1, VoiceDictionary.parse(raw).size)
    }

    @Test
    fun `empty dictionary keeps the text`() {
        assertEquals(" как есть ", VoiceDictionary.EMPTY.apply(" как есть "))
    }
}
