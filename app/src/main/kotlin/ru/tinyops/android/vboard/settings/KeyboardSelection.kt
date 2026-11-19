package ru.tinyops.android.vboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import ru.tinyops.android.vboard.KeyboardService

/** Which keyboard the system shows in text fields, and how to switch it to this one. */
internal object KeyboardSelection {
    fun isOwnSelected(context: Context): Boolean {
        val current =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return ComponentName.unflattenFromString(current) == ComponentName(context, KeyboardService::class.java)
    }

    /** Opens the keyboard picker, or the keyboard list of the system when this keyboard is not enabled there yet. */
    fun choose(context: Context) {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return
        if (manager.enabledInputMethodList.any { it.packageName == context.packageName }) {
            manager.showInputMethodPicker()
            return
        }
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Calls [listener] on the main thread whenever the user switches keyboards; the result stops it. */
    fun observe(
        context: Context,
        listener: () -> Unit,
    ): () -> Unit {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = listener()
            }
        val uri = Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD)
        context.contentResolver.registerContentObserver(uri, false, observer)
        return { context.contentResolver.unregisterContentObserver(observer) }
    }
}
