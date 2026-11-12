package ru.tinyops.android.vboard.ui

import android.view.View

/**
 * The undo arrow of a custom button held for a while: a preview of the undo shows above [anchor] until the
 * finger lets go. [onPeek] hears the id of the button when the hold starts (true) and when it ends (false).
 */
internal class UndoPeek(
    val preview: UndoPreviewPopup,
    private val anchor: View,
) {
    var onPeek: ((id: String, peek: Boolean) -> Unit)? = null

    companion object {
        /** The id under which the proofread button holds its undo arrow. */
        const val PROOFREAD = "proofread"
    }

    private var holding: String? = null

    fun begin(id: String) {
        holding = id
        preview.show(anchor, id)
        onPeek?.invoke(id, true)
    }

    fun end() {
        val id = holding ?: return
        holding = null
        preview.dismiss()
        onPeek?.invoke(id, false)
    }
}
