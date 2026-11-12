package ru.tinyops.android.vboard.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LogReportTest {
    private val header = "App 1.0, Android 15"

    @Test
    fun `report starts with the device header and lists entries in the given order`() {
        val entries =
            listOf(
                ErrorLogEntry(2_000, "B", "second", "stack line"),
                ErrorLogEntry(1_000, "A", "first", null),
            )

        val text = LogReport.render(header, entries) { "T$it" }

        assertEquals("App 1.0, Android 15\n\nT2000 B: second\nstack line\n\nT1000 A: first", text)
    }

    @Test
    fun `report of an empty log is only the header`() {
        assertEquals(header, LogReport.render(header, emptyList()) { "T$it" })
    }

    @Test
    fun `file name carries the date and time`() {
        assertEquals("vboard-log-20261108-115335.txt", LogReport.fileName(LocalDateTime.of(2026, 11, 8, 11, 53, 35)))
    }

    @Test
    fun `an exit is described with its reason, description and process`() {
        val text =
            ExitHistory.describe(
                "native crash",
                "signal 11 (SIGSEGV)",
                "ru.tinyops.android.vboard",
                5_000,
                { "T$it" },
            )

        assertTrue(text, text.contains("native crash"))
        assertTrue(text, text.contains("signal 11 (SIGSEGV)"))
        assertTrue(text, text.contains("ru.tinyops.android.vboard"))
        assertTrue(text, text.contains("T5000"))
    }

    @Test
    fun `an exit without a description omits it`() {
        val text = ExitHistory.describe("crash", null, "p", 1, { "T$it" })

        assertEquals("T1 p: crash", text)
    }
}
