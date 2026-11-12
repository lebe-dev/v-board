package ru.tinyops.android.vboard.voice.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The sound that goes with a phrase, and the microphone buffer that feeds the phrases. */
class SpeechCaptureTest {
    private val second = VadRecorder.SAMPLE_RATE.toLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the first phrase keeps everything recorded before it`() {
        val opening =
            VadOpening.range(first = true, start = 2 * second, tailStart = 0, tailEnd = 3 * second, lastEnd = 0)

        assertEquals(0L until 2 * second, opening)
    }

    @Test
    fun `the first phrase gets nothing when it starts at the very beginning or too late`() {
        assertTrue(VadOpening.range(true, start = 0, tailStart = 0, tailEnd = second, lastEnd = 0).isEmpty())
        val late = VadOpening.FIRST_MAX_SAMPLES + 1
        val opening = VadOpening.range(true, start = late, tailStart = 0, tailEnd = late + second, lastEnd = 0)
        assertTrue(opening.isEmpty())
    }

    @Test
    fun `a later phrase gets the short preroll before it`() {
        val start = 10 * second
        val opening =
            VadOpening.range(
                false,
                start,
                tailStart = 5 * second,
                tailEnd = 11 * second,
                lastEnd = 6 * second,
            )

        assertEquals(start - VadOpening.PREROLL_SAMPLES until start, opening)
    }

    @Test
    fun `the preroll never reaches into the previous phrase`() {
        val start = 10 * second
        val lastEnd = start - 100
        val opening = VadOpening.range(false, start, tailStart = 5 * second, tailEnd = 11 * second, lastEnd = lastEnd)

        assertEquals(lastEnd until start, opening)
    }

    @Test
    fun `the opening takes only the sound still retained`() {
        val start = 10 * second
        val tailStart = start - 50
        val opening = VadOpening.range(false, start, tailStart = tailStart, tailEnd = 11 * second, lastEnd = 0)

        assertEquals(tailStart until start, opening)
    }

    @Test
    fun `a phrase is its opening followed by the speech as 16-bit pcm`() {
        val tail = ByteArrayOutputStream().apply { write(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)) }
        // Retained samples 100..103; the opening is the last two of them.
        val pcm = VadOpening.pcm(102L..103L, tail, tailStart = 100, samples = floatArrayOf(0f, 1f, -1f, 2f))

        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val values = ShortArray(shorts.remaining()).also(shorts::get)
        assertArrayEquals(shortArrayOf(3, 4, 0, Short.MAX_VALUE, (-Short.MAX_VALUE).toShort(), Short.MAX_VALUE), values)
    }

    @Test
    fun `a phrase without an opening is the speech alone`() {
        val pcm = VadOpening.pcm(LongRange.EMPTY, ByteArrayOutputStream(), tailStart = 0, samples = floatArrayOf(0.5f))

        assertEquals(2, pcm.size)
    }

    @Test
    fun `the microphone is read into whole windows and a short last one`() =
        runBlocking {
            val source = ByteArray(10) { it.toByte() }
            var position = 0
            val capture =
                BufferedPcmCapture(scope, windowBytes = 4, keepReading = { true }) { target, offset, count ->
                    // Small reads, as a device gives them; nothing left ends the capture.
                    val n = minOf(count, 3, source.size - position)
                    if (n <= 0) return@BufferedPcmCapture 0
                    source.copyInto(target, offset, position, position + n)
                    position += n
                    n
                }

            val frames = withTimeout(5_000) { buildList { for (frame in capture.frames) add(frame.toList()) } }

            assertEquals(listOf(listOf<Byte>(0, 1, 2, 3), listOf<Byte>(4, 5, 6, 7), listOf<Byte>(8, 9)), frames)
        }

    @Test
    fun `stopping the microphone ends the frames`() =
        runBlocking {
            var reading = true
            var reads = 0
            val capture =
                BufferedPcmCapture(scope, windowBytes = 2, keepReading = { reading }) { _, _, count ->
                    if (++reads == 3) reading = false
                    count
                }

            val frames = withTimeout(5_000) { buildList { for (frame in capture.frames) add(frame) } }

            assertEquals(3, frames.size)
            assertTrue(capture.job.isCompleted)
        }
}
