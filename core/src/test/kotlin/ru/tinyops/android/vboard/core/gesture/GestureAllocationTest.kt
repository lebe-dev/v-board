package ru.tinyops.android.vboard.core.gesture

import com.sun.management.ThreadMXBean
import ru.tinyops.android.vboard.core.bundledDictionary
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.core.phoneGeometry
import ru.tinyops.android.vboard.core.swipe
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Garbage from decoding is what wakes the collector while the user types, so the decoder must
 * recycle its working memory. Live suggestions decode repeatedly while the finger moves.
 */
class GestureAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private fun allocatedBytes(block: () -> Unit): Long {
        val id = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(id)
        block()
        return threads.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun `a warm decode stays within the allocation budget`() {
        val geometry = phoneGeometry(Language.RUSSIAN)
        val engine =
            GestureEngine().apply {
                setLayout(GestureLayoutFactory.create(geometry, xdpi = 420f, ydpi = 420f))
                setLanguageModel(bundledDictionary(Language.RUSSIAN).languageModel)
            }
        val gesture = swipe(geometry, "достопримечательность")
        repeat(WARM_UP_DECODES) { engine.decode(gesture, "", complete = true) }

        val allocated = (1..MEASURED_DECODES).minOf { allocatedBytes { engine.decode(gesture, "", complete = true) } }

        assertTrue(allocated <= BUDGET_BYTES, "a warm decode allocated $allocated bytes, budget is $BUDGET_BYTES")
    }

    private companion object {
        const val WARM_UP_DECODES = 3
        const val MEASURED_DECODES = 5
        const val BUDGET_BYTES = 64 * 1024L
    }
}
