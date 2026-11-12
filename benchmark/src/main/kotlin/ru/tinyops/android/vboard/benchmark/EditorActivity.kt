package ru.tinyops.android.vboard.benchmark

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText

/** A plain text field: the keyboard is an input method, so something has to request it. */
class EditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                requestFocus()
            }
        setContentView(editor)
    }
}
