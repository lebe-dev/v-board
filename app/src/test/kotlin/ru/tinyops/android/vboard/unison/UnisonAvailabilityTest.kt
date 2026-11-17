package ru.tinyops.android.vboard.unison

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import app.unison.bridge.BridgeContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The states the integration page and the keyboard tell apart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnisonAvailabilityTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `an app that is not on the phone is missing`() {
        assertEquals(UnisonAvailability.MISSING, UnisonAvailability.of(context))
    }

    @Test
    fun `an app signed with another key is refused`() {
        install()
        assertEquals(UnisonAvailability.REFUSED, UnisonAvailability.of(context))
    }

    @Test
    fun `an app that lets the keyboard in is ready`() {
        install()
        shadowOf(context).grantPermissions(BridgeContract.PERMISSION)
        assertEquals(UnisonAvailability.READY, UnisonAvailability.of(context))
    }

    @Suppress("DEPRECATION")
    private fun install() {
        val info =
            PackageInfo().apply {
                packageName = BridgeContract.PACKAGE
                versionName = "1.0"
                applicationInfo = ApplicationInfo().apply { packageName = BridgeContract.PACKAGE }
            }
        shadowOf(context.packageManager).installPackage(info)
    }
}
