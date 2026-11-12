package ru.tinyops.android.vboard.stt.spatial

import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout

/**
 * Scores how well a touch coordinate matches a target. Implementations return a
 * cost in the negative-log-likelihood domain: lower is better and a perfect hit
 * should be zero.
 */
interface SpatialModel {
    /** Cost of a touch at `(x, y)` targeting an arbitrary point. */
    fun matchCostToPoint(
        x: Float,
        y: Float,
        targetX: Float,
        targetY: Float,
    ): Double

    /** Cost of a touch at `(x, y)` targeting the center of [key]. */
    fun matchCost(
        x: Float,
        y: Float,
        key: Key,
    ): Double = matchCostToPoint(x, y, key.centerX, key.centerY)
}

/**
 * Isotropic Gaussian model. The cost is the squared distance scaled by
 * `1 / (2 * sigma^2)`, i.e. the negative log of an (unnormalized) Gaussian
 * density.
 *
 * @param sigma standard deviation in keyboard-space pixels
 * @param sigmaY optional vertical standard deviation; defaults to [sigma]
 */
class GaussianSpatialModel(
    val sigma: Double,
    val sigmaY: Double = sigma,
) : SpatialModel {
    init {
        require(sigma > 0.0) { "sigma must be positive" }
        require(sigmaY > 0.0) { "sigmaY must be positive" }
    }

    private val invTwoSigmaSqX = 1.0 / (2.0 * sigma * sigma)
    private val invTwoSigmaSqY = 1.0 / (2.0 * sigmaY * sigmaY)

    override fun matchCostToPoint(
        x: Float,
        y: Float,
        targetX: Float,
        targetY: Float,
    ): Double {
        val dx = (x - targetX).toDouble()
        val dy = (y - targetY).toDouble()
        return dx * dx * invTwoSigmaSqX + dy * dy * invTwoSigmaSqY
    }

    override fun toString(): String = "GaussianSpatialModel(sigma=$sigma, sigmaY=$sigmaY)"

    companion object {
        const val DEFAULT_SIGMA_FRACTION: Double = 0.35

        /** Builds a model whose sigma scales with the layout's average key height. */
        fun forLayout(
            layout: KeyboardLayout,
            sigmaFraction: Double = DEFAULT_SIGMA_FRACTION,
        ): GaussianSpatialModel {
            val sigma = layout.averageKeyHeight * sigmaFraction
            return GaussianSpatialModel(sigma)
        }
    }
}
