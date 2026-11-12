package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.core.dict.bigramTableOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeuralWordSearchTest {
    private val search = NeuralWordSearch(TEST_SPEC, beamSize = 4, maxSteps = 35)

    @Test
    fun `the best scored word comes first`() {
        val trie = trieOf("да" to 1.0, "до" to 1.0, "дом" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits = mapOf("д" to mapOf('а' to 0f, 'о' to 4f), "до" to mapOf('м' to 4f)),
                eos = mapOf("до" to 3f, "дом" to 4f),
            )
        assertEquals(listOf("дом", "до", "да"), search.search(model, trie.root, limit = 4))
    }

    @Test
    fun `a letter the dictionary forces counts with the probability the model gives it`() {
        // After "а" the dictionary allows only "т", so the search scores it as certain, though the
        // model reads another letter there. The whole word then loses to "бык", which the model reads.
        val trie = trieOf("ат" to 1.0, "бык" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits =
                    mapOf(
                        "" to mapOf('а' to 2f, 'б' to 1.9f),
                        "а" to mapOf('х' to 5f),
                        "б" to mapOf('ы' to 5f),
                        "бы" to mapOf('к' to 5f),
                    ),
                eos = mapOf("ат" to 5f, "бык" to 5f),
            )
        assertEquals(listOf("бык", "ат"), search.search(model, trie.root, limit = 4))
    }

    @Test
    fun `only the best words are rescored, each in one call`() {
        val trie = trieOf("аа" to 1.0, "аб" to 1.0, "ав" to 1.0, "аг" to 1.0, "ад" to 1.0, "ае" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits = mapOf("а" to mapOf('а' to 6f, 'б' to 5f, 'в' to 4f, 'г' to 3f, 'д' to 2f, 'е' to 1f)),
            )
        val search = NeuralWordSearch(TEST_SPEC, beamSize = 6, maxSteps = 35, rescored = 4)
        search.search(model, trie.root, limit = 4)
        assertEquals(listOf("аа", "аб", "ав", "аг"), model.rescoredWords)
    }

    @Test
    fun `a single word is not rescored`() {
        val trie = trieOf("кот" to 1.0)
        val model = ScriptedModel(TEST_SPEC, logits = emptyMap())
        assertEquals(listOf("кот"), search.search(model, trie.root, limit = 4))
        assertTrue(model.rescoredWords.isEmpty(), "rescored ${model.rescoredWords}")
    }

    @Test
    fun `only words of the dictionary come out even if the model prefers other letters`() {
        val trie = trieOf("кот" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits = mapOf("" to mapOf('я' to 10f, 'к' to 1f), "к" to mapOf('о' to 1f), "ко" to mapOf('т' to 1f)),
                eos = mapOf("кот" to 1f),
            )
        assertEquals(listOf("кот"), search.search(model, trie.root, limit = 4))
    }

    @Test
    fun `a letter the model does not have is reached through its base letter, the more frequent word first`() {
        val trie = trieOf("все" to 1.0, "всё" to 3.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits = mapOf("" to mapOf('в' to 5f), "в" to mapOf('с' to 5f), "вс" to mapOf('е' to 5f)),
                eos = mapOf("все" to 5f),
            )
        assertEquals(listOf("всё", "все"), search.search(model, trie.root, limit = 4))
    }

    @Test
    fun `a word with a hyphen comes out whole`() {
        val trie = trieOf("изза" to 1.0, "из-за" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits = mapOf("из" to mapOf('-' to 5f, 'з' to 1f)),
                eos = mapOf("из-за" to 5f, "изза" to 5f),
            )
        assertEquals(listOf("из-за", "изза"), search.search(model, trie.root, limit = 4))
    }

    @Test
    fun `the model is not asked when the dictionary leaves a single continuation`() {
        val trie = trieOf("привет" to 1.0)
        val model = ScriptedModel(TEST_SPEC, logits = emptyMap())
        assertEquals(listOf("привет"), search.search(model, trie.root, limit = 4))
        assertTrue(model.decodedPrefixes.isEmpty(), "asked for ${model.decodedPrefixes}")
    }

    @Test
    fun `skipping forced steps does not change the scores`() {
        // "ст" forces "о" and then a choice; without skipping, the forced step would add log(1) = 0.
        val trie = trieOf("сто" to 1.0, "стол" to 1.0, "стон" to 1.0, "сон" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits =
                    mapOf(
                        "" to mapOf('с' to 3f),
                        "с" to mapOf('т' to 3f, 'о' to 1f),
                        "сто" to mapOf('л' to 3f, 'н' to 1f),
                    ),
                eos = mapOf("сто" to 0f),
            )
        val result = search.search(model, trie.root, limit = 4)
        assertEquals("стол", result.first())
        assertEquals(setOf("стол", "стон", "сто", "сон"), result.toSet())
        assertTrue("ст" !in model.decodedPrefixes)
    }

    @Test
    fun `at most the limit of words is returned`() {
        val trie = trieOf("а" to 1.0, "б" to 1.0, "в" to 1.0, "г" to 1.0)
        val model = ScriptedModel(TEST_SPEC, logits = mapOf("" to mapOf('а' to 3f, 'б' to 2f, 'в' to 1f)))
        assertEquals(listOf("а", "б"), search.search(model, trie.root, limit = 2))
    }

    /** Two words the model cannot tell apart: the first in token order wins without a context. */
    private val tie =
        ScriptedModel(
            TEST_SPEC,
            logits = mapOf("" to mapOf('а' to 5f), "а" to mapOf('б' to 5f, 'в' to 5f)),
            eos = mapOf("аб" to 5f, "ав" to 5f),
        )
    private val tieTrie = trieOf("аб" to 1.0, "ав" to 1.0)
    private val bigrams = BigramTable.read(bigramTableOf(mapOf(("мы" to "ав") to 50L, ("мы" to "аб") to 1L)))

    @Test
    fun `the previous word decides between words the model scores alike`() {
        assertEquals(listOf("аб", "ав"), search.search(tie, tieTrie.root, limit = 4))
        val context = bigrams.context("мы")
        assertEquals(listOf("ав", "аб"), search.search(tie, tieTrie.root, limit = 4, context = context))
    }

    @Test
    fun `a previous word with no pairs for the candidates changes nothing`() {
        val context = BigramTable.read(bigramTableOf(mapOf(("мы" to "дом") to 5L))).context("мы")
        assertEquals(listOf("аб", "ав"), search.search(tie, tieTrie.root, limit = 4, context = context))
    }

    @Test
    fun `a word reached through a folded letter is looked up folded`() {
        val trie = trieOf("жа" to 1.0, "жё" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits = mapOf("" to mapOf('ж' to 5f), "ж" to mapOf('а' to 5f, 'е' to 5f)),
                eos = mapOf("жа" to 5f, "же" to 5f),
            )
        val context = BigramTable.read(bigramTableOf(mapOf(("мы" to "же") to 50L, ("мы" to "жа") to 1L))).context("мы")
        assertEquals(listOf("жа", "жё"), search.search(model, trie.root, limit = 4))
        assertEquals(listOf("жё", "жа"), search.search(model, trie.root, limit = 4, context = context))
    }

    @Test
    fun `with a previous word more words are rescored`() {
        val trie = trieOf("аа" to 1.0, "аб" to 1.0, "ав" to 1.0, "аг" to 1.0, "ад" to 1.0, "ае" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits = mapOf("а" to mapOf('а' to 6f, 'б' to 5f, 'в' to 4f, 'г' to 3f, 'д' to 2f, 'е' to 1f)),
            )
        val search = NeuralWordSearch(TEST_SPEC, beamSize = 6, maxSteps = 35, rescored = 2, contextRescored = 5)
        search.search(model, trie.root, limit = 4, context = bigrams.context("мы"))
        assertEquals(listOf("аа", "аб", "ав", "аг", "ад"), model.rescoredWords)
    }

    @Test
    fun `an empty dictionary gives no words`() {
        val model = ScriptedModel(TEST_SPEC, logits = emptyMap())
        assertEquals(emptyList(), search.search(model, trieOf().root, limit = 4))
    }
}
