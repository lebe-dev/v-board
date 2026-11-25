package ru.tinyops.android.vboard.core.dict

import ru.tinyops.android.vboard.core.bundledDictionary
import ru.tinyops.android.vboard.core.layout.KeyboardLayouts
import ru.tinyops.android.vboard.core.layout.KeyboardMode
import ru.tinyops.android.vboard.core.layout.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapSuggesterTest {
    private val ruProximity = KeyProximity.fromSpec(KeyboardLayouts.spec(Language.RUSSIAN, KeyboardMode.LETTERS))
    private val enProximity = KeyProximity.fromSpec(KeyboardLayouts.spec(Language.ENGLISH, KeyboardMode.LETTERS))

    @Test
    fun `corrects one-edit typos in russian and english`() {
        val ru = TapSuggester(bundledDictionary(Language.RUSSIAN), ruProximity)
        val en = TapSuggester(bundledDictionary(Language.ENGLISH), enProximity)

        val ruResult = ru.suggest("пртвет")
        assertEquals("привет", ruResult.words.first())
        assertEquals("привет", ruResult.autocorrect)

        val enResult = en.suggest("wrold")
        assertEquals("world", enResult.words.first())
        assertEquals("world", enResult.autocorrect)

        val transposed = ru.suggest("привте")
        assertEquals("привет", transposed.words.first())
        assertEquals("привет", transposed.autocorrect)
    }

    @Test
    fun `never autocorrects a valid dictionary word`() {
        val ru = TapSuggester(bundledDictionary(Language.RUSSIAN), ruProximity)
        val result = ru.suggest("привет")
        assertNull(result.autocorrect)
        assertTrue("привет" in result.words)
    }

    @Test
    fun `restores yo for russian words typed with e`() {
        val dict = Dictionary.parse("ещё\t500\nёжик\t100\nвсе\t400\nвсё\t390\n".byteInputStream())
        val suggester = TapSuggester(dict, ruProximity)

        val eshe = suggester.suggest("еще")
        assertEquals("ещё", eshe.words.first())
        assertEquals("ещё", eshe.autocorrect)

        val ezhik = suggester.suggest("ежик")
        assertEquals("ёжик", ezhik.words.first())
        assertEquals("ёжик", ezhik.autocorrect)

        val vse = suggester.suggest("все")
        assertNull(vse.autocorrect)
        assertTrue("всё" in vse.words)
    }

    @Test
    fun `keyboard proximity prefers neighboring key over distant key`() {
        // On English QWERTY, 'e' is next to 'w' and 'r', whereas 'p' is far away.
        // Typing "hpllo" vs "hwllo": both "hello" and "hallo" have equal frequency here.
        val dict = Dictionary.parse("hello\t100\nhallo\t100\n".byteInputStream())
        val suggester = TapSuggester(dict, enProximity)

        // 'w' is right next to 'e' (distance ~1), far from 'a' -> prefers "hello".
        assertEquals("hello", suggester.suggest("hwllo").words.first())
        // 's' is right next to 'a', far from 'e' -> prefers "hallo".
        assertEquals("hallo", suggester.suggest("hsllo").words.first())
    }

    @Test
    fun `spatial tap coordinates disambiguate between equally frequent neighbors`() {
        // On QWERTY top row: q=0.5, w=1.5, e=2.5, r=3.5, t=4.5.
        val equalDict = Dictionary.parse("cat\t100\ncae\t100\n".byteInputStream())
        val suggester = TapSuggester(equalDict, enProximity)

        val nearT = TapPoint(4.1f, 0.5f)
        val nearE = TapPoint(2.9f, 0.5f)

        val tapsTowardT = listOf(null, null, nearT)
        val tapsTowardE = listOf(null, null, nearE)
        assertEquals("cat", suggester.suggest("car", taps = tapsTowardT).words.first())
        assertEquals("cae", suggester.suggest("car", taps = tapsTowardE).words.first())
    }

    @Test
    fun `fuzzy prefix completes unfinished words with a typo`() {
        val ru = TapSuggester(bundledDictionary(Language.RUSSIAN), ruProximity)
        val result = ru.suggest("прмве", options = TapSuggestOptions(fuzzyPrefix = true, twoEdits = false))
        assertTrue("привет" in result.words, "Expected 'привет' in ${result.words}")

        val disabled = ru.suggest("прмве", options = TapSuggestOptions(fuzzyPrefix = false, twoEdits = false))
        assertTrue("привет" !in disabled.words, "Unexpected fuzzy completion when disabled: ${disabled.words}")
    }

    @Test
    fun `two-edit corrections fix longer words with two typos`() {
        val ru = TapSuggester(bundledDictionary(Language.RUSSIAN), ruProximity)
        // "привет" with two typos: 'и'->'м' and 'е'->'у' (both nearby keys or transposition "прмвте")
        val result = ru.suggest("прмвте", options = TapSuggestOptions(twoEdits = true))
        assertEquals("привет", result.words.first())

        val disabled = ru.suggest("прмвте", options = TapSuggestOptions(twoEdits = false))
        assertTrue("привет" !in disabled.words)
    }

    @Test
    fun `disabling corrections falls back to exact prefix completions`() {
        val ru = TapSuggester(bundledDictionary(Language.RUSSIAN), ruProximity)
        val result = ru.suggest("пртвет", options = TapSuggestOptions(corrections = false))
        assertTrue(result.words.isEmpty())
        assertNull(result.autocorrect)
    }

    @Test
    fun `autocorrect still works when strip typo suggestions are disabled`() {
        val ru = TapSuggester(bundledDictionary(Language.RUSSIAN), ruProximity)
        val result = ru.suggest("пртвет", options = TapSuggestOptions(corrections = false, autocorrect = true))
        assertEquals("привет", result.autocorrect)
        assertEquals(listOf("привет"), result.words)
    }

    @Test
    fun `cursor corrections fix a missing letter in a long russian word`() {
        val ru = TapSuggester(bundledDictionary(Language.RUSSIAN), ruProximity)
        assertTrue("предложение" in ru.corrections("прдложение"))
    }

    @Test
    fun `cursor corrections fix a rare corpus typo when a much more common word is one edit away`() {
        val ru = TapSuggester(bundledDictionary(Language.RUSSIAN), ruProximity)
        assertEquals("сделать", ru.corrections("сделат").firstOrNull())
        assertTrue(ru.corrections("семиклассница").isEmpty())
        assertTrue(ru.corrections("докторе").isEmpty())
        assertTrue(ru.corrections("главах").isEmpty())
        assertTrue(ru.corrections("далекое").isEmpty())
    }

    @Test
    fun `reused two-edit scratch matches fresh computation across lengths`() {
        val dict =
            Dictionary.parse(
                "apple\t10\napricot\t10\nbanana\t10\nband\t10\napp\t10\napplication\t10\n".byteInputStream(),
            )
        val scratch = TapEditDistance.TwoEditScratch(maxTypedLength = 7)
        for (query in listOf("aple", "apricto", "bannana", "appl")) {
            for (index in 0 until dict.size) {
                assertEquals(
                    TapEditDistance.withinTwo(query, dict.words, index),
                    TapEditDistance.withinTwo(query, dict.words, index, scratch = scratch),
                    "Mismatch for query=$query word=${dict.words.wordAt(index)}",
                )
            }
        }
    }
}
