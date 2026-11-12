package ru.tinyops.android.vboard.stt.decode

/**
 * Tuning parameters for the beam-search gesture decoder.
 *
 * @param beamWidth number of hypotheses kept per word-length level
 * @param maxResults number of final suggestions returned for a completed gesture
 * @param preemptiveMaxResults number of suggestions returned for in-progress gestures
 * @param maxWordLength maximum number of characters expanded per hypothesis
 * @param spatialWeight weight of the spatial-model cost
 * @param languageWeight weight of the language-model score
 * @param sigmaFraction gaussian sigma as a fraction of the average key height
 * @param minDistanceFraction downsampling threshold as a fraction of the average key height
 * @param maxTimeGapMillis maximum time gap before a point is force-kept
 * @param unexplainedPointCost cost charged, while pruning the beam, for each gesture
 * point a word prefix has not reached yet (in the spatial model's cost units)
 */
data class DecodeConfig(
    val beamWidth: Int = 100,
    val maxResults: Int = 8,
    val preemptiveMaxResults: Int = 3,
    val maxWordLength: Int = 24,
    val spatialWeight: Double = 1.0,
    val languageWeight: Double = 1.0,
    val sigmaFraction: Double = 0.35,
    val minDistanceFraction: Double = 0.1,
    val maxTimeGapMillis: Double = 100.0,
    val unexplainedPointCost: Double = 1.0,
) {
    init {
        require(beamWidth > 0) { "beamWidth must be positive" }
        require(maxResults > 0) { "maxResults must be positive" }
        require(maxWordLength > 0) { "maxWordLength must be positive" }
        require(sigmaFraction > 0.0) { "sigmaFraction must be positive" }
        require(unexplainedPointCost >= 0.0) { "unexplainedPointCost must not be negative" }
    }
}
