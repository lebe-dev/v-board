package ru.tinyops.android.vboard.core.gesture.neural

/**
 * The input of the model for one gesture, `length` points long.
 *
 * @param trajectory for each point: x, y, velocity x, velocity y, acceleration x, acceleration y
 * @param keys for each point: the token of the nearest key
 */
class SwipeFeatures(
    val trajectory: FloatArray,
    val keys: LongArray,
    val length: Int,
) {
    companion object {
        const val TRAJECTORY_SIZE = 6
    }
}

/**
 * An encoder-decoder model. A gesture is encoded once, then the decoder is asked about prefixes of
 * the word. Not thread-safe: confine to one thread.
 */
interface SwipeSequenceModel {
    /** Encodes a gesture; later [nextLogits] calls refer to it. */
    fun encode(features: SwipeFeatures)

    /**
     * The logits of the token following the first [length] entries of [tokens].
     * The result is valid until the next call.
     */
    fun nextLogits(
        tokens: LongArray,
        length: Int,
    ): FloatArray

    /**
     * The logits after each of the first [length] entries of [tokens], in one decoder pass: the row
     * at `i * numClasses` predicts the token that follows entry `i`.
     * The result is valid until the next call.
     */
    fun logitsAfterEach(
        tokens: LongArray,
        length: Int,
    ): FloatArray
}
