package ru.tinyops.android.vboard.stt.model

import ru.tinyops.android.vboard.stt.qwertyLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardLayoutTest {
    @Test
    fun `key center and dimensions are derived from the hitbox`() {
        val key = Key(1, "a", left = 10f, top = 20f, right = 110f, bottom = 120f)
        assertEquals(60f, key.centerX)
        assertEquals(70f, key.centerY)
        assertEquals(100f, key.width)
        assertEquals(100f, key.height)
    }

    @Test
    fun `only single letter labels count as character keys`() {
        assertTrue(Key(1, "a", 0f, 0f, 1f, 1f).isCharacter)
        assertEquals('a', Key(1, "A", 0f, 0f, 1f, 1f).character)
        assertNull(Key(2, "shift", 0f, 0f, 1f, 1f).character)
        assertNull(Key(3, "", 0f, 0f, 1f, 1f).character)
        assertNull(Key(4, "12", 0f, 0f, 1f, 1f).character)
    }

    @Test
    fun `contains respects the hitbox`() {
        val key = Key(1, "a", 0f, 0f, 100f, 100f)
        assertTrue(key.contains(50f, 50f))
        assertTrue(key.contains(0f, 0f))
        assertTrue(key.contains(100f, 100f))
        assertTrue(!key.contains(101f, 50f))
    }

    @Test
    fun `looks up character keys case-insensitively`() {
        val layout = qwertyLayout()
        val q = layout.keyForCharacter('q')
        assertNotNull(q)
        assertEquals(layout.keyForCharacter('Q'), q)
        assertNull(layout.keyForCharacter('1'))
    }

    @Test
    fun `hit testing finds the key under a point`() {
        val layout = qwertyLayout()
        val q = layout.keyForCharacter('q')!!
        assertEquals(q, layout.keyAt(q.centerX, q.centerY))
        assertNull(layout.keyAt(-50f, -50f))
    }

    @Test
    fun `averages are computed over character keys`() {
        val layout = qwertyLayout()
        assertEquals(100f, layout.averageKeyHeight)
        assertEquals(100f, layout.averageKeyWidth)
        assertEquals(26, layout.characterKeys.size)
    }

    @Test
    fun `falls back to overall size when no character keys exist`() {
        val layout = KeyboardLayout(500f, 400f, 160f, 160f, keys = emptyList())
        assertEquals(400f, layout.averageKeyHeight)
        assertEquals(500f, layout.averageKeyWidth)
    }
}
