package ru.tinyops.android.vboard.unison

import android.content.Context
import android.content.pm.PackageManager
import app.unison.bridge.BridgeContract

/** Whether Unison can carry the clipboard of this keyboard. */
internal enum class UnisonAvailability {
    /** Unison is not installed. */
    MISSING,

    /** Unison is installed but signed with another key, so it refuses the keyboard. */
    REFUSED,

    /** Unison is installed and lets the keyboard connect. */
    READY,
    ;

    companion object {
        fun of(context: Context): UnisonAvailability =
            when {
                !installed(context) -> MISSING
                context.checkSelfPermission(BridgeContract.PERMISSION) != PackageManager.PERMISSION_GRANTED -> REFUSED
                else -> READY
            }

        fun installed(context: Context): Boolean =
            try {
                context.packageManager.getPackageInfo(BridgeContract.PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
    }
}
