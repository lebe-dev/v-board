package ru.tinyops.android.vboard.core.gesture.neural

import java.io.InputStream
import java.util.Properties

/** A linear normalization `(value - mean) / std`. */
data class Normalization(
    val mean: Float,
    val std: Float,
) {
    fun apply(value: Float): Float = (value - mean) / std
}

/**
 * What a trained model expects, written next to it when it is exported: the token tables and the
 * numbers that turn a gesture into its input.
 *
 * @param decoderTokens the letter of each decoder token, by id; ids above are special tokens
 * @param numClasses the size of the decoder output
 * @param keyLabels the letter of each key token, by id
 * @param gridKeys the letters that have a key on the training grid
 * @param anchorLeft x of the center of the left key of the top row (`й`) on the training grid
 * @param anchorRight x of the center of the right key of the top row (`х`)
 * @param anchorTop y of the center of the top row
 * @param anchorBottom y of the center of the third row (`я`)
 */
data class SwipeModelSpec(
    val decoderTokens: String,
    val numClasses: Int,
    val eosId: Int,
    val sosId: Int,
    val keyLabels: String,
    val gridKeys: String,
    val anchorLeft: Float,
    val anchorRight: Float,
    val anchorTop: Float,
    val anchorBottom: Float,
    val xMin: Float,
    val xMax: Float,
    val yMin: Float,
    val yMax: Float,
    val velocityX: Normalization,
    val velocityY: Normalization,
    val accelerationX: Normalization,
    val accelerationY: Normalization,
) {
    companion object {
        /** Reads `key=value` lines in UTF-8. Throws [IllegalArgumentException] when a value is missing or broken. */
        fun parse(input: InputStream): SwipeModelSpec {
            val values = Properties().apply { input.bufferedReader(Charsets.UTF_8).use { load(it) } }

            fun text(key: String): String = requireNotNull(values.getProperty(key)) { "Missing $key in the model spec" }

            fun number(key: String): Float =
                requireNotNull(text(key).toFloatOrNull()) { "Malformed $key in the model spec" }

            fun whole(key: String): Int = requireNotNull(text(key).toIntOrNull()) { "Malformed $key in the model spec" }

            fun normalization(key: String): Normalization {
                val std = number("${key}_std")
                require(std > 0f) { "The standard deviation of $key must be positive" }
                return Normalization(number("${key}_mean"), std)
            }

            return SwipeModelSpec(
                decoderTokens = text("decoder_tokens"),
                numClasses = whole("num_classes"),
                eosId = whole("eos_id"),
                sosId = whole("sos_id"),
                keyLabels = text("key_labels"),
                gridKeys = text("grid_keys"),
                anchorLeft = number("anchor_left"),
                anchorRight = number("anchor_right"),
                anchorTop = number("anchor_top"),
                anchorBottom = number("anchor_bottom"),
                xMin = number("x_min"),
                xMax = number("x_max"),
                yMin = number("y_min"),
                yMax = number("y_max"),
                velocityX = normalization("velocity_x"),
                velocityY = normalization("velocity_y"),
                accelerationX = normalization("acceleration_x"),
                accelerationY = normalization("acceleration_y"),
            )
        }
    }
}
