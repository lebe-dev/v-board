package ru.tinyops.android.vboard.core.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceLevelTest {
    private fun tone(amplitude: Double): ByteArray {
        val samples = 512
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (amplitude * Short.MAX_VALUE * sin(2 * PI * i / 32)).toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    @Test
    fun `silence is the lowest level`() {
        assertEquals(0f, VoiceLevel.fromPcm16(ByteArray(1024)))
        assertEquals(0f, VoiceLevel.fromPcm16(ByteArray(0)))
    }

    @Test
    fun `a loud voice fills the bubble and a quiet one lights it a little`() {
        val loud = VoiceLevel.fromPcm16(tone(0.5))
        val quiet = VoiceLevel.fromPcm16(tone(0.01))
        assertEquals(1f, loud)
        assertTrue(quiet > 0f && quiet < 0.5f, "quiet was $quiet")
    }

    @Test
    fun `louder sound gives a higher level`() {
        assertTrue(VoiceLevel.fromPcm16(tone(0.05)) > VoiceLevel.fromPcm16(tone(0.01)))
    }

    @Test
    fun `the system recognizer level stays within the range`() {
        assertEquals(0f, VoiceLevel.fromRmsDb(-2f))
        assertEquals(1f, VoiceLevel.fromRmsDb(12f))
        val middle = VoiceLevel.fromRmsDb(4f)
        assertTrue(middle > 0f && middle < 1f)
    }

    @Test
    fun `the follower rises fast and falls slowly`() {
        val follower = LevelFollower()
        val up = follower.next(1f, FRAME_MILLIS)
        val afterRise = follower.next(1f, FRAME_MILLIS * 5)
        val down = follower.next(0f, FRAME_MILLIS)
        assertTrue(up > 0.3f, "rise was $up")
        assertTrue(afterRise > 0.9f)
        assertTrue(down > 0.7f, "fall was $down")
    }

    @Test
    fun `the follower settles at silence`() {
        val follower = LevelFollower()
        follower.next(1f, FRAME_MILLIS * 10)
        repeat(100) { follower.next(0f, FRAME_MILLIS) }
        assertTrue(follower.value < 0.01f)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
