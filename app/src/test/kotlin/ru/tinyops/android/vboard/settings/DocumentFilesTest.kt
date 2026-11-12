package ru.tinyops.android.vboard.settings

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentFilesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `writing over a longer file leaves nothing of the old text`() {
        val file = File(context.cacheDir, "export.txt").apply { writeText("a much longer text that was here before") }

        DocumentFiles.writeText(context, Uri.fromFile(file), "short")

        assertEquals("short", file.readText())
    }

    @Test
    fun `the text is written as UTF-8`() {
        val file = File(context.cacheDir, "export.txt")

        DocumentFiles.writeText(context, Uri.fromFile(file), "Журнал ошибок")

        assertEquals("Журнал ошибок", file.readText(Charsets.UTF_8))
    }
}
