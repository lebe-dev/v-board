package ru.tinyops.android.vboard.voice.engine

import com.k2fsa.sherpa.onnx.Vad
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts 16 kHz 16-bit mono PCM into Silero's speech segments, each with the sound just before it
 * ([VadOpening]). Positions are absolute samples of the sound given. Not thread-safe: a shared
 * [vad] needs the caller's lock. Taken from Govorun.
 */
internal class SpeechSegmenter(
    private val vad: Vad,
) {
    class Segment(
        val pcm: ByteArray,
        val startSample: Long,
        val speechSamples: Int,
    )

    // The sound since the last segment, with the absolute sample of its first byte.
    private val retained = ByteArrayOutputStream()
    private var retainedStart = 0L
    private var position = 0L
    private var lastEnd = 0L

    /** Segments cut so far. */
    var emitted = 0
        private set

    /** Whether Silero hears speech now. */
    fun speech(): Boolean = vad.isSpeechDetected()

    /** One window (the last one may be shorter): into the retained sound and Silero; the segments it ended. */
    fun accept(window: ByteArray): List<Segment> {
        if (retained.size() == 0) retainedStart = position
        if (retained.size() + window.size > MAX_RETAINED_BYTES) {
            // Memory stays bounded: the older half goes. Silero cuts long before this on natural speech.
            val dropped = retained.size() / 2 / 2 * 2
            val kept = retained.toByteArray().copyOfRange(dropped, retained.size())
            retained.reset()
            retained.write(kept)
            retainedStart += dropped / 2
        }
        retained.write(window)
        val shorts = ByteBuffer.wrap(window).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        // A short window is fed too, padded only at its end.
        val samples = FloatArray(WINDOW_SAMPLES) { if (shorts.hasRemaining()) shorts.get() / PCM_SCALE else 0f }
        vad.acceptWaveform(samples)
        position += WINDOW_SAMPLES
        return drain()
    }

    /** The end of the sound: what Silero still holds. */
    fun flush(): List<Segment> {
        vad.flush()
        return drain()
    }

    /** The sound retained since the last segment. */
    fun rest(): ByteArray = retained.toByteArray()

    fun restBytes(): Int = retained.size()

    private fun drain(): List<Segment> {
        if (vad.empty()) return emptyList()
        val segments = ArrayList<Segment>(1)
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val start = segment.start.toLong()
            val retainedEnd = retainedStart + retained.size() / 2
            val opening = VadOpening.range(emitted == 0, start, retainedStart, retainedEnd, lastEnd)
            segments +=
                Segment(
                    pcm = VadOpening.pcm(opening, retained, retainedStart, segment.samples),
                    startSample = if (opening.isEmpty()) start else opening.first,
                    speechSamples = segment.samples.size,
                )
            // Silence confirmed: everything retained so far went with the segment.
            retained.reset()
            retainedStart = retainedEnd
            lastEnd = start + segment.samples.size
            emitted++
        }
        return segments
    }

    companion object {
        const val WINDOW_SAMPLES = 512
        const val MAX_RETAINED_BYTES = VadRecorder.SAMPLE_RATE * 2 * 60
        private const val PCM_SCALE = 32_768f
    }
}

/**
 * What a speech segment gets from the sound just before it. Silero starts a phrase a little after
 * its first sound, so the first segment keeps everything recorded before it (up to six seconds)
 * and a later one up to 450 ms, never reaching into the previous segment. Govorun measured the
 * first word cut in 5.6% of short phrases without it and in none with it.
 */
internal object VadOpening {
    const val FIRST_MAX_SAMPLES = VadRecorder.SAMPLE_RATE * 6L
    const val PREROLL_SAMPLES = VadRecorder.SAMPLE_RATE * 450L / 1000
    private const val PCM_MAX = 32_767f

    /** Absolute samples to put before a segment starting at [start], within the retained [tailStart]..[tailEnd]. */
    fun range(
        first: Boolean,
        start: Long,
        tailStart: Long,
        tailEnd: Long,
        lastEnd: Long,
    ): LongRange {
        val from =
            when {
                !first -> maxOf(start - PREROLL_SAMPLES, lastEnd)
                start in 1..FIRST_MAX_SAMPLES -> 0L
                else -> return LongRange.EMPTY
            }
        val begin = maxOf(from, tailStart)
        val end = minOf(start, tailEnd)
        return if (begin < end) begin until end else LongRange.EMPTY
    }

    /** The segment as 16-bit PCM, prefixed with [opening] (from [range]) taken from [tail] at [tailStart]. */
    fun pcm(
        opening: LongRange,
        tail: ByteArrayOutputStream,
        tailStart: Long,
        samples: FloatArray,
    ): ByteArray {
        val openingBytes = if (opening.isEmpty()) 0 else ((opening.last - opening.first + 1) * 2).toInt()
        val pcm = ByteArray(samples.size * 2 + openingBytes)
        if (openingBytes > 0) {
            val from = ((opening.first - tailStart) * 2).toInt()
            tail.toByteArray().copyInto(pcm, 0, from, from + openingBytes)
        }
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(openingBytes)
        for (sample in samples) {
            val value = (sample * PCM_MAX).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(value.toShort())
        }
        return pcm
    }
}
