package ru.tinyops.android.vboard.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportEnvironmentTest {
    @Test
    fun `manufacturer and model become a lowercase slug`() {
        assertEquals("google-pixel-11-pro", ReportEnvironment.of("Google", "Pixel 11 Pro"))
    }

    @Test
    fun `a model that already starts with the manufacturer is not repeated`() {
        assertEquals("xiaomi-23127pc0cg", ReportEnvironment.of("Xiaomi", "Xiaomi 23127PC0CG"))
    }

    @Test
    fun `odd characters collapse into single dashes`() {
        assertEquals("samsung-sm-s911b", ReportEnvironment.of("samsung", "  SM_S911B / "))
    }

    @Test
    fun `an empty name falls back to unknown-device`() {
        assertEquals("unknown-device", ReportEnvironment.of("", " "))
    }
}
