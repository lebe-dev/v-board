package ru.tinyops.android.vboard

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Puts a panel such as the clipboard history in place of the strip and the keys of the input view, and
 * brings the keys back.
 *
 * @param root the input view, if there is one
 */
internal class KeysCover(
    private val root: () -> LinearLayout?,
    private val strip: () -> View?,
    private val keys: () -> View?,
) {
    /** The input view is there to show a panel in. */
    val ready: Boolean get() = root() != null && strip() != null && keys() != null

    /** Shows [panel] at the height of the strip and the keys, adding it to the input view the first time. */
    fun show(panel: View) {
        val root = root() ?: return
        val strip = strip() ?: return
        val keys = keys() ?: return
        val height = strip.height + keys.height
        if (panel.parent == null) {
            root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        }
        panel.layoutParams.height = height
        strip.visibility = View.GONE
        keys.visibility = View.GONE
        panel.visibility = View.VISIBLE
    }

    /** Hides [panel] and shows the keys again; returns whether the panel was shown. */
    fun hide(panel: View?): Boolean {
        if (panel == null || panel.visibility != View.VISIBLE) return false
        panel.visibility = View.GONE
        strip()?.visibility = View.VISIBLE
        keys()?.visibility = View.VISIBLE
        return true
    }
}
