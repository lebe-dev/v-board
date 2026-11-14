package ru.tinyops.android.vboard.core.voice

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/** How loud the microphone is, from 0 (silence) to 1 (a raised voice), for the animation of the voice strip. */
object VoiceLevel {
    /** The level of a window of 16-bit little-endian mono PCM. */
    fun fromPcm16(
        pcm: ByteArray,
        length: Int = pcm.size,
    ): Float {
        val samples = length / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = (pcm[i * 2].toInt() and BYTE_MASK) or (pcm[i * 2 + 1].toInt() shl Byte.SIZE_BITS)
            val normalized = sample.toShort() / FULL_SCALE
            sum += normalized * normalized
        }
        val rms = sqrt(sum / samples)
        if (rms <= 0.0) return 0f
        return scale(DECIBELS * log10(rms), PCM_FLOOR_DB, PCM_CEILING_DB)
    }

    /** The level of the value the system recognizer reports in `onRmsChanged`. */
    fun fromRmsDb(rmsDb: Float): Float = scale(rmsDb.toDouble(), SYSTEM_FLOOR_DB, SYSTEM_CEILING_DB)

    private fun scale(
        db: Double,
        floor: Double,
        ceiling: Double,
    ): Float = ((db - floor) / (ceiling - floor)).coerceIn(0.0, 1.0).toFloat()

    private const val BYTE_MASK = 0xFF
    private const val FULL_SCALE = 32_768.0
    private const val DECIBELS = 20.0
    private const val PCM_FLOOR_DB = -55.0
    private const val PCM_CEILING_DB = -20.0
    private const val SYSTEM_FLOOR_DB = -2.0
    private const val SYSTEM_CEILING_DB = 10.0
}

/** Smooths the level between frames: the animation jumps up with a word and calms down after it. */
class LevelFollower {
    var value: Float = 0f
        private set

    fun next(
        target: Float,
        elapsedMillis: Long,
    ): Float {
        val time = if (target > value) ATTACK_MILLIS else RELEASE_MILLIS
        val share = 1.0 - exp(-elapsedMillis.coerceAtLeast(0L) / time)
        value += ((target - value) * share).toFloat()
        return value
    }

    fun reset() {
        value = 0f
    }

    private companion object {
        const val ATTACK_MILLIS = 30.0
        const val RELEASE_MILLIS = 250.0
    }
}
