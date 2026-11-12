package ru.tinyops.android.vboard

import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import ru.tinyops.android.vboard.core.gesture.neural.SwipeFeatures
import ru.tinyops.android.vboard.core.gesture.neural.SwipeSequenceModel

/** Runs a program written by `scripts/swipe_model/export_model.py`, which takes one sequence without a batch axis. */
internal class ExecuTorchSwipeModel(
    private val module: Module,
    private val numClasses: Int,
) : SwipeSequenceModel {
    private var encoded: EValue? = null

    init {
        // Loading the methods here runs their native setup while the model opens, under the load
        // guard, instead of on the first gesture.
        module.loadMethod(METHOD_ENCODE)
        module.loadMethod(METHOD_DECODE)
    }

    override fun encode(features: SwipeFeatures) {
        val length = features.length.toLong()
        val trajectoryShape = longArrayOf(length, SwipeFeatures.TRAJECTORY_SIZE.toLong())
        val trajectory = Tensor.fromBlob(features.trajectory, trajectoryShape)
        val keys = Tensor.fromBlob(features.keys, longArrayOf(length))
        encoded = module.execute(METHOD_ENCODE, EValue.from(trajectory), EValue.from(keys)).first()
    }

    override fun nextLogits(
        tokens: LongArray,
        length: Int,
    ): FloatArray = decode(tokens, length).copyOfRange((length - 1) * numClasses, length * numClasses)

    override fun logitsAfterEach(
        tokens: LongArray,
        length: Int,
    ): FloatArray = decode(tokens, length)

    /** The decoder program returns the logits after every entry, `length` rows of [numClasses]. */
    private fun decode(
        tokens: LongArray,
        length: Int,
    ): FloatArray {
        val memory = checkNotNull(encoded) { "A gesture must be encoded before decoding" }
        val input = Tensor.fromBlob(tokens.copyOf(length), longArrayOf(length.toLong()))
        return module
            .execute(METHOD_DECODE, EValue.from(input), memory)
            .first()
            .toTensor()
            .dataAsFloatArray
    }

    private companion object {
        const val METHOD_ENCODE = "encode"
        const val METHOD_DECODE = "decode"
    }
}
