package ru.tinyops.android.vboard.core.gesture.neural

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelLoadGuardTest {
    private class MemoryMarker : ModelLoadGuard.Marker {
        var value = false
        val seen = ArrayList<Boolean>()

        override var loading: Boolean
            get() = value
            set(it) {
                value = it
                seen += it
            }
    }

    private val marker = MemoryMarker()
    private var crashes = 0
    private val guard = ModelLoadGuard(marker, onPreviousCrash = { crashes++ })

    @Test
    fun `a successful load is marked while it runs and cleared after`() {
        var markedDuringLoad = false
        val result =
            guard.open {
                markedDuringLoad = marker.value
                "model"
            }
        assertEquals("model", result)
        assertTrue(markedDuringLoad)
        assertFalse(marker.value)
        assertEquals(0, crashes)
    }

    @Test
    fun `a load that throws clears the mark and rethrows`() {
        assertFailsWith<UnsatisfiedLinkError> { guard.open { throw UnsatisfiedLinkError("dlopen failed") } }
        assertFalse(marker.value)
        assertEquals(0, crashes)
    }

    @Test
    fun `a mark left by a process that died while loading skips the model`() {
        marker.value = true
        var loaded = false
        val result =
            guard.open {
                loaded = true
                "model"
            }
        assertNull(result)
        assertFalse(loaded)
        assertFalse(marker.value)
        assertEquals(1, crashes)
    }

    @Test
    fun `after reporting a crash the next load runs again`() {
        marker.value = true
        guard.open { "model" }
        assertEquals("model", guard.open { "model" })
        assertEquals(1, crashes)
    }
}
