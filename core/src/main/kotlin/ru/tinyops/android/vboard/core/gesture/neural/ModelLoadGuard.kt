package ru.tinyops.android.vboard.core.gesture.neural

/**
 * Keeps a native crash in the model runtime from taking the keyboard down on every start.
 *
 * A crash inside native code cannot be caught, and the model is opened as soon as the keyboard
 * shows, so a device the runtime cannot serve would kill the keyboard each time it opens. The
 * guard leaves a persistent mark while the model loads; a mark found on the next load means the
 * process died in the middle of it, so the model is skipped and [onPreviousCrash] lets the caller
 * turn the experimental engine off.
 */
class ModelLoadGuard(
    private val marker: Marker,
    private val onPreviousCrash: () -> Unit,
) {
    /** A flag that survives the death of the process; writes must reach the disk before returning. */
    interface Marker {
        var loading: Boolean
    }

    /** Runs [load] under the mark; null without running it when the previous load crashed. */
    fun <T> open(load: () -> T?): T? {
        if (marker.loading) {
            marker.loading = false
            onPreviousCrash()
            return null
        }
        marker.loading = true
        try {
            return load()
        } finally {
            marker.loading = false
        }
    }
}
