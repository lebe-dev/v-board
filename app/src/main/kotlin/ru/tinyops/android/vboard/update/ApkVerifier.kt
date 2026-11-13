package ru.tinyops.android.vboard.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/** Checks that a downloaded APK is this app, signed with the key of the installed one. */
internal object ApkVerifier {
    fun isUpdateOf(
        context: Context,
        apk: File,
    ): Boolean {
        val manager = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = manager.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val signers = archive.signingInfo?.apkContentsSigners.orEmpty()
        return signers.isNotEmpty() &&
            signers.all {
                manager.hasSigningCertificate(context.packageName, it.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)
            }
    }
}
