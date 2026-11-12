package ru.tinyops.android.vboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.clipboard.ClipPayload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClipboardContentTest {
    private val imageUri = Uri.parse("content://dev.example.provider/images/1")

    private fun imageClip(mime: String) = ClipData(ClipDescription("image", arrayOf(mime)), ClipData.Item(imageUri))

    @Test
    fun `empty clipboard has no content`() {
        assertNull(ClipboardContent.from(null) { null })
    }

    @Test
    fun `plain text is read as text`() {
        val clip = ClipData.newPlainText("label", "Привет, мир")
        assertEquals(ClipPayload.Text("Привет, мир"), ClipboardContent.from(clip) { null })
    }

    @Test
    fun `blank text counts as empty`() {
        val clip = ClipData.newPlainText("label", "")
        assertEquals(null, ClipboardContent.from(clip) { null })
    }

    @Test
    fun `image declared by the clip description is an image`() {
        val content = ClipboardContent.from(imageClip("image/png")) { null }
        assertEquals(ClipPayload.Image(imageUri.toString(), "image/png"), content)
    }

    @Test
    fun `uri without a declared type is resolved through the content resolver`() {
        val clip =
            ClipData(ClipDescription("uri", arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST)), ClipData.Item(imageUri))
        val content = ClipboardContent.from(clip) { if (it == imageUri) "image/jpeg" else null }
        assertEquals(ClipPayload.Image(imageUri.toString(), "image/jpeg"), content)
    }

    @Test
    fun `uri of a non image file is not pasted`() {
        val clip = ClipData(ClipDescription("file", arrayOf("application/pdf")), ClipData.Item(imageUri))
        assertEquals(null, ClipboardContent.from(clip) { "application/pdf" })
    }

    @Test
    fun `text wins over an image in the same item`() {
        val clip = ClipData(ClipDescription("both", arrayOf("image/png")), ClipData.Item("caption", null, imageUri))
        assertEquals(ClipPayload.Text("caption"), ClipboardContent.from(clip) { null })
    }

    @Test
    fun `image type is picked from the types the editor accepts`() {
        assertEquals("image/png", ClipboardContent.acceptedImageType("image/png", arrayOf("image/*")))
        assertEquals("image/png", ClipboardContent.acceptedImageType("image/png", arrayOf("image/gif", "image/png")))
        assertNull(ClipboardContent.acceptedImageType("image/png", arrayOf("image/gif")))
        assertNull(ClipboardContent.acceptedImageType("image/png", emptyArray()))
    }
}
