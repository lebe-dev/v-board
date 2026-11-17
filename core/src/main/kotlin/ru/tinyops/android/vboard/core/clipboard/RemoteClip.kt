package ru.tinyops.android.vboard.core.clipboard

/**
 * An item copied on another device of the user. It carries no content: [preview] is a short text, empty for an
 * image, and [createdAt] is the copy time by the clock of that device.
 */
data class RemoteClip(
    val id: String,
    val image: Boolean,
    val preview: String,
    val device: String,
    val createdAt: Long,
)
