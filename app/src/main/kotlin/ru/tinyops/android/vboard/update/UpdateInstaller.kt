package ru.tinyops.android.vboard.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import ru.tinyops.android.vboard.diagnostics.AppLog
import java.io.File

/** Hands a downloaded APK to the system installer, which asks the user to confirm. */
internal object UpdateInstaller {
    /** Whether the user allows this app to install apps; without it the installer refuses. */
    fun allowed(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install(
        context: Context,
        apk: File,
    ) {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val status = Intent(context, UpdateInstallReceiver::class.java)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    status,
                    // The installer writes the status into the intent; it is explicit, so only this app receives it.
                    // nosemgrep: mutable-pending-intent
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the progress of an install: the confirmation the user must give, or the outcome. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                // The updates page opens it: an activity in the foreground may start it.
                UpdateDownload.installerAnswered(UpdateDownload.State.Confirm(confirm))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "The update is installed")
                UpdateDownload.installerAnswered(UpdateDownload.State.Idle)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "The user cancelled the update")
                UpdateDownload.installerAnswered(UpdateDownload.State.Idle)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.error(TAG, "The installer refused the update: status $status, $message", null)
                UpdateDownload.installerAnswered(UpdateDownload.State.Failed(UpdateDownload.Failure.INSTALL))
            }
        }
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) UpdateDownload.clear(context)
    }

    private companion object {
        const val TAG = "UpdateInstaller"
    }
}
