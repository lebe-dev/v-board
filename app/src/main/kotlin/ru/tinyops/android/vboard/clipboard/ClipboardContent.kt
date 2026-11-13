package ru.tinyops.android.vboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import ru.tinyops.android.vboard.core.clipboard.ClipPayload

/** Reads what a [ClipData] holds, reduced to what the keyboard can insert. */
object ClipboardContent {
    private const val ANY_IMAGE = "image/*"

    /**
     * Reads the first item of [clip]; null when there is nothing to insert. Text wins over an
     * image; an image is a content uri whose type is declared by the clip or reported by [typeOf].
     */
    fun from(
        clip: ClipData?,
        typeOf: (Uri) -> String?,
    ): ClipPayload? {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val text = item.text?.toString().orEmpty()
        if (text.isNotEmpty()) return ClipPayload.Text(text)
        val uri = item.uri ?: return null
        val mimeType = imageType(clip.description, uri, typeOf) ?: return null
        return ClipPayload.Image(uri.toString(), mimeType)
    }

    private fun imageType(
        description: ClipDescription,
        uri: Uri,
        typeOf: (Uri) -> String?,
    ): String? {
        for (index in 0 until description.mimeTypeCount) {
            val declared = description.getMimeType(index)
            if (ClipDescription.compareMimeTypes(declared, ANY_IMAGE)) return declared
        }
        return typeOf(uri)?.takeIf { ClipDescription.compareMimeTypes(it, ANY_IMAGE) }
    }

    /** True for clips the source app marked sensitive (passwords from a manager): never kept or shown. */
    fun isSensitive(clip: ClipData): Boolean =
        clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true

    /** The editor's accepted type that fits [mimeType], or null when the editor takes no such image. */
    fun acceptedImageType(
        mimeType: String,
        accepted: Array<String>,
    ): String? = accepted.firstOrNull { ClipDescription.compareMimeTypes(mimeType, it) }?.let { mimeType }

    /** Hands the image at [uri] to the editor as rich content of [mimeType]; false when the editor refused it. */
    fun commitImage(
        connection: InputConnection,
        info: EditorInfo,
        uri: Uri,
        mimeType: String,
    ): Boolean =
        InputConnectionCompat.commitContent(
            connection,
            info,
            InputContentInfoCompat(uri, ClipDescription("image", arrayOf(mimeType)), null),
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null,
        )
}
