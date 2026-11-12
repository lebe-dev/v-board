package ru.tinyops.android.vboard.core.clipboard

/** What a clipboard entry holds. An image is referenced by a locator that the app resolves. */
sealed interface ClipPayload {
    data class Text(
        val text: String,
    ) : ClipPayload

    data class Image(
        val source: String,
        val mimeType: String,
    ) : ClipPayload
}

/** One remembered clipboard item; [id] is its copy time in milliseconds. */
data class ClipEntry(
    val id: Long,
    val payload: ClipPayload,
)
