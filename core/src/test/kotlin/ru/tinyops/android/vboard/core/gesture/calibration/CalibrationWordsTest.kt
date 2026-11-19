package ru.tinyops.android.vboard.core.gesture.calibration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationWordsTest {
    @Test
    fun `every word is in the dictionary and asked once`() {
        val dictionary =
            File("src/main/dict/ru.tsv").useLines { lines -> lines.map { it.substringBefore('\t') }.toSet() }
        val words = CalibrationWords.RUSSIAN
        assertEquals(words.size, words.toSet().size)
        assertTrue(words.all { it in dictionary }, words.filterNot { it in dictionary }.toString())
    }
}
