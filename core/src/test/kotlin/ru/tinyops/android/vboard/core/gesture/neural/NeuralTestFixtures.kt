package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.stt.lm.WordTrie
import kotlin.math.ln

/** A spec like the one of a real bundle: the training grid is 1080 wide, its letters have no `ё` and no `ъ`. */
internal val TEST_SPEC =
    SwipeModelSpec.parse(
        """
        decoder_tokens=-абвгдежзийклмнопрстуфхцчшщъыьэюя
        num_classes=35
        eos_id=33
        sos_id=36
        key_labels=абвгдеёжзийклмнопрстуфхцчшщъыьэюя-
        grid_keys=йцукенгшщзхфывапролджэячсмитьбю
        anchor_left=49.5
        anchor_right=1031
        anchor_top=92
        anchor_bottom=400
        x_min=0
        x_max=1080
        y_min=0
        y_max=667
        velocity_x_mean=0.1
        velocity_x_std=2
        velocity_y_mean=-0.1
        velocity_y_std=2
        acceleration_x_mean=0
        acceleration_x_std=0.5
        acceleration_y_mean=0
        acceleration_y_std=0.5
        """.trimIndent().byteInputStream(),
    )

internal fun trieOf(vararg words: Pair<String, Double>): WordTrie =
    WordTrie().apply {
        for ((word, frequency) in words) insert(word, ln(frequency), frequency)
    }

/** Answers from a table of `prefix -> token -> logit`; the prefix is the text generated so far. */
internal class ScriptedModel(
    private val spec: SwipeModelSpec,
    private val logits: Map<String, Map<Char, Float>>,
    private val eos: Map<String, Float> = emptyMap(),
) : SwipeSequenceModel {
    var encodedLength = 0
    val decodedPrefixes = ArrayList<String>()

    /** The text of every sequence passed to [logitsAfterEach], in call order. */
    val rescoredWords = ArrayList<String>()

    override fun encode(features: SwipeFeatures) {
        encodedLength = features.length
    }

    override fun nextLogits(
        tokens: LongArray,
        length: Int,
    ): FloatArray {
        val prefix = textOf(tokens, length)
        decodedPrefixes += prefix
        return row(prefix)
    }

    override fun logitsAfterEach(
        tokens: LongArray,
        length: Int,
    ): FloatArray {
        rescoredWords += textOf(tokens, length)
        val out = FloatArray(length * spec.numClasses)
        for (entry in 1..length) row(textOf(tokens, entry)).copyInto(out, (entry - 1) * spec.numClasses)
        return out
    }

    /** The letters of the first [length] tokens after the start token; the end token adds none. */
    private fun textOf(
        tokens: LongArray,
        length: Int,
    ): String =
        buildString {
            for (i in 1 until length) {
                val token = tokens[i].toInt()
                if (token < spec.decoderTokens.length) append(spec.decoderTokens[token])
            }
        }

    private fun row(prefix: String): FloatArray {
        val out = FloatArray(spec.numClasses) { UNLIKELY }
        logits[prefix]?.forEach { (char, value) -> out[spec.decoderTokens.indexOf(char)] = value }
        eos[prefix]?.let { out[spec.eosId] = it }
        return out
    }

    private companion object {
        const val UNLIKELY = -20f
    }
}
