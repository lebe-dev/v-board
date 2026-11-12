package ru.tinyops.android.vboard.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ErrorLogStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = START
    private val file: File get() = File(folder.root, "log.jsonl")
    private val store by lazy { ErrorLogStore(file, clock = { now }) }

    @Test
    fun `an empty store has no entries`() {
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `entries come back newest first with all their fields`() {
        store.append("Tag", "first", "trace 1")
        now += 1_000
        store.append("Other", "second", null)

        val entries = store.entries()

        assertEquals(listOf("second", "first"), entries.map { it.message })
        assertEquals("Tag", entries[1].tag)
        assertEquals("trace 1", entries[1].stack)
        assertEquals(START, entries[1].time)
        assertNull(entries[0].stack)
    }

    @Test
    fun `entries older than seven days are dropped`() {
        store.append("Tag", "old", null)
        now += TimeUnit.DAYS.toMillis(7) + 1
        store.append("Tag", "fresh", null)

        assertEquals(listOf("fresh"), store.entries().map { it.message })
    }

    @Test
    fun `an entry just inside seven days is kept`() {
        store.append("Tag", "edge", null)
        now += TimeUnit.DAYS.toMillis(7)

        assertEquals(listOf("edge"), store.entries().map { it.message })
    }

    @Test
    fun `old entries leave the file at the next write`() {
        store.append("Tag", "old", null)
        now += TimeUnit.DAYS.toMillis(8)
        store.append("Tag", "fresh", null)

        assertEquals(1, file.readLines().size)
    }

    @Test
    fun `the log keeps only the latest entries when errors repeat`() {
        repeat(ErrorLogStore.MAX_ENTRIES + 10) { store.append("Tag", "e$it", null) }

        val entries = store.entries()

        assertEquals(ErrorLogStore.MAX_ENTRIES, entries.size)
        assertEquals("e${ErrorLogStore.MAX_ENTRIES + 9}", entries.first().message)
    }

    @Test
    fun `a long stack trace is cut`() {
        store.append("Tag", "big", "x".repeat(ErrorLogStore.MAX_STACK_CHARS * 2))

        assertEquals(
            ErrorLogStore.MAX_STACK_CHARS,
            store
                .entries()
                .single()
                .stack
                ?.length,
        )
    }

    @Test
    fun `a damaged line is skipped and the rest is read`() {
        store.append("Tag", "good", null)
        file.appendText("not json\n")

        assertEquals(listOf("good"), store.entries().map { it.message })
    }

    @Test
    fun `clearing removes everything`() {
        store.append("Tag", "gone", null)
        store.clear()

        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `multiline messages survive`() {
        store.append("Tag", "line 1\nline 2", "a\nb")

        val entry = store.entries().single()
        assertEquals("line 1\nline 2", entry.message)
        assertEquals("a\nb", entry.stack)
    }

    private companion object {
        const val START = 1_800_000_000_000L
    }
}
