package ru.tinyops.android.vboard

import android.content.Context
import ru.tinyops.android.vboard.core.gesture.SlideEngine
import ru.tinyops.android.vboard.core.gesture.neural.ModelLoadGuard
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import ru.tinyops.android.vboard.diagnostics.ExitHistory

private const val TAG = "NeuralLoadGuard"

/**
 * Turns the experimental engine off after it crashed the keyboard while loading its model, and tells the user
 * through [onSwitchedOff], which may be called on any thread.
 */
internal fun neuralLoadGuard(
    context: Context,
    prefs: KeyboardPrefs,
    onSwitchedOff: () -> Unit,
): ModelLoadGuard =
    ModelLoadGuard(
        marker =
            object : ModelLoadGuard.Marker {
                override var loading: Boolean
                    get() = prefs.neuralModelLoading
                    set(value) {
                        prefs.neuralModelLoading = value
                    }
            },
        onPreviousCrash = {
            val exits = ExitHistory.recent(context)
            val details = if (exits.isEmpty()) "no exit records from the system" else exits.joinToString("\n")
            AppLog.error(
                TAG,
                "The app died while loading the slide to type model on its previous start, so the " +
                    "model was skipped and the engine was switched from " +
                    "${prefs.slideEngine.code} to ${SlideEngine.DEFAULT.code}. " +
                    "Recent exits recorded by the system:\n$details",
            )
            ErrorReporting.capture(IllegalStateException("The slide to type model crashed while loading"))
            prefs.slideEngine = SlideEngine.DEFAULT
            onSwitchedOff()
        },
    )
