package ru.tinyops.android.vboard.core.layout

import ru.tinyops.android.vboard.core.phoneGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardGeometryTest {
    private val geometry = phoneGeometry(Language.ENGLISH)

    @Test
    fun `key centers resolve to their own key`() {
        for (key in geometry.keys) {
            assertEquals(key, geometry.keyAt(key.bounds.centerX, key.bounds.centerY))
        }
    }

    @Test
    fun `every point of the keyboard maps to a key`() {
        var y = 0f
        while (y < geometry.height) {
            var x = 0f
            while (x < geometry.width) {
                assertNotNull(geometry.keyAt(x, y), "no key at ($x, $y)")
                x += 7f
            }
            y += 7f
        }
    }

    @Test
    fun `points outside the keyboard map to nothing`() {
        assertNull(geometry.keyAt(-1f, 10f))
        assertNull(geometry.keyAt(10f, geometry.height + 1f))
    }

    @Test
    fun `visible bounds sit inside the hit box`() {
        for (key in geometry.keys) {
            val b = key.bounds
            val h = key.hitBox
            assertTrue(b.left >= h.left && b.right <= h.right && b.top >= h.top && b.bottom <= h.bottom, "$key")
        }
    }

    @Test
    fun `middle row is indented by half a key`() {
        val q = geometry.keys.first { it.spec.output == "q" }
        val a = geometry.keys.first { it.spec.output == "a" }
        assertEquals(q.bounds.centerX + q.bounds.width / 2f + 14f / 2f, a.bounds.centerX, 0.5f)
    }

    @Test
    fun `left edge of the indented row belongs to its first key`() {
        val a = geometry.keys.first { it.spec.output == "a" }
        assertEquals(a, geometry.keyAt(1f, a.bounds.centerY))
    }

    @Test
    fun `empty area produces no keys`() {
        val empty = KeyboardGeometry(geometry.spec, 0f, 0f, GeometryMetrics(0f, 0f, 0f, 0f, 0f))
        assertTrue(empty.keys.isEmpty())
    }
}
