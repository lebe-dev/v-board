package ru.tinyops.android.vboard.stt.lm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordTrieTest {
    @Test
    fun `inserts words and marks terminals`() {
        val trie = WordTrie()
        trie.insert("cat", logProb = -1.0, frequency = 5.0)
        trie.insert("car", logProb = -2.0, frequency = 3.0)
        trie.insert("care", logProb = -3.0, frequency = 1.0)

        assertTrue(trie.contains("cat"))
        assertTrue(trie.contains("car"))
        assertTrue(trie.contains("care"))
        assertFalse(trie.contains("ca"))
        assertFalse(trie.contains("card"))
        assertEquals(3, trie.wordCount)
    }

    @Test
    fun `prefixes are not terminals but exist as nodes`() {
        val trie = WordTrie()
        trie.insert("care", logProb = -1.0, frequency = 1.0)
        val ca = trie.root.child('c')!!.child('a')!!
        assertFalse(ca.isWord)
        assertEquals(1, ca.childCount)
        assertNotNull(ca.child('r'))
    }

    @Test
    fun `re-inserting the same word does not inflate the count`() {
        val trie = WordTrie()
        trie.insert("the", logProb = -1.0, frequency = 1.0)
        trie.insert("the", logProb = -2.0, frequency = 2.0)
        assertEquals(1, trie.wordCount)
        val node =
            trie.root
                .child('t')!!
                .child('h')!!
                .child('e')!!
        assertEquals(-2.0, node.logProb)
        assertEquals(2.0, node.frequency)
    }

    @Test
    fun `node count counts every node`() {
        val trie = WordTrie()
        trie.insert("ab", logProb = 0.0)
        trie.insert("ac", logProb = 0.0)
        // root -> a -> b, root -> a -> c  => 4 nodes
        assertEquals(4, trie.nodeCount())
    }

    @Test
    fun `keeps every child of a node with many children`() {
        val trie = WordTrie()
        val letters = "щабвгдеёжзийклмнопрстуфхцчшъыьэюя"
        for (c in letters) trie.insert("$c$c", logProb = -1.0)
        assertEquals(letters.length, trie.root.childCount)
        for (c in letters) assertTrue(trie.contains("$c$c"), "$c")
        assertEquals(letters.length, trie.wordCount)
        assertEquals(1 + 2 * letters.length, trie.nodeCount())
    }

    @Test
    fun `a word may be the prefix of an earlier word`() {
        val trie = WordTrie()
        trie.insert("hello", logProb = -2.0, frequency = 2.0)
        trie.insert("hell", logProb = -1.0, frequency = 1.0)
        val hell =
            trie.root
                .child('h')!!
                .child('e')!!
                .child('l')!!
                .child('l')!!
        assertEquals("hell", hell.word)
        assertEquals(-1.0, hell.logProb)
        assertEquals(1, hell.childCount)
        assertEquals("hello", hell.child('o')!!.word)
    }

    @Test
    fun `a node that is not a word has no word data`() {
        val trie = WordTrie()
        trie.insert("ab", logProb = -1.0, frequency = 3.0)
        val a = trie.root.child('a')!!
        assertNull(a.word)
        assertEquals(Double.NEGATIVE_INFINITY, a.logProb)
        assertEquals(0.0, a.frequency)
        assertNull(a.child('z'))
    }

    @Test
    fun `stays usable after compacting`() {
        val trie = WordTrie()
        trie.insert("ab", logProb = -1.0)
        trie.compact()
        trie.insert("abc", logProb = -2.0)
        trie.insert("xyz", logProb = -3.0)
        assertTrue(trie.contains("ab"))
        assertTrue(trie.contains("abc"))
        assertTrue(trie.contains("xyz"))
        assertEquals(3, trie.wordCount)
    }

    @Test
    fun `empty word is rejected`() {
        val trie = WordTrie()
        try {
            trie.insert("", logProb = 0.0)
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `a trie rebuilt from its arrays is identical and reads words from the source`() {
        val original = WordTrie()
        val words = listOf("cat", "car", "dog")
        words.forEachIndexed { index, word -> original.insert(word, -1.0 - index, 10.0 + index) }
        original.compact()

        val rebuilt = WordTrie.fromArrays(original.toArrays(), words::get)

        assertEquals(original.nodeCount(), rebuilt.nodeCount())
        assertEquals(3, rebuilt.wordCount)
        assertEquals(
            "car",
            rebuilt.root
                .child('c')
                ?.child('a')
                ?.child('r')
                ?.word,
        )
        assertEquals(
            -2.0,
            rebuilt.root
                .child('c')
                ?.child('a')
                ?.child('r')
                ?.logProb,
        )
        assertEquals(
            12.0,
            rebuilt.root
                .child('d')
                ?.child('o')
                ?.child('g')
                ?.frequency,
        )
        assertTrue(rebuilt.contains("dog"))
        assertFalse(rebuilt.contains("do"))
    }

    @Test
    fun `a trie rebuilt from arrays cannot grow`() {
        val original = WordTrie().apply { insert("cat", -1.0) }
        val rebuilt = WordTrie.fromArrays(original.toArrays()) { "cat" }
        assertFailsWith<IllegalStateException> { rebuilt.insert("dog", -1.0) }
    }

    @Test
    fun `mismatched arrays are rejected`() {
        val arrays = WordTrie().apply { insert("cat", -1.0) }.toArrays()
        val broken = arrays.copy(firstChild = IntArray(1))
        assertFailsWith<IllegalArgumentException> { WordTrie.fromArrays(broken) { "cat" } }
    }
}
