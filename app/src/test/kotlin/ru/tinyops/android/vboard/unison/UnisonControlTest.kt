package ru.tinyops.android.vboard.unison

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.content.edit
import androidx.core.net.toUri
import app.unison.bridge.BridgeContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.R

/** The keyboard says so when the sync is on but Unison is not on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnisonControlTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = UnisonPrefs(context)
    private val messages = mutableListOf<Int>()
    private var installed = false
    private lateinit var control: UnisonControl

    @Before
    fun setUp() {
        context.getSharedPreferences("unison_prefs", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        control =
            UnisonControl(
                context,
                UnisonHost({ null }, {}, {}, { _, _ -> }, { messages += it }),
                prefs,
                { NOW },
                { installed },
            )
    }

    @After
    fun tearDown() = control.destroy()

    @Test
    fun `a missing app is reported once while the sync is on`() {
        prefs.enabled = true
        control.sync()
        control.sync()
        assertEquals(listOf(R.string.unison_status_missing), messages)
    }

    @Test
    fun `nothing is reported while the sync is off`() {
        control.sync()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `an installed app is not reported`() {
        installed = true
        prefs.enabled = true
        control.sync()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `turning the sync off and on reports again`() {
        prefs.enabled = true
        control.sync()
        prefs.enabled = false
        control.sync()
        prefs.enabled = true
        control.sync()
        assertEquals(2, messages.size)
    }

    @Test
    fun `after the app appears the sync is not reported again`() {
        prefs.enabled = true
        control.sync()
        installed = true
        packageEvent(Intent.ACTION_PACKAGE_ADDED)
        control.sync()
        assertEquals(1, messages.size)
    }

    @Test
    fun `after the app is removed it is reported again`() {
        installed = true
        prefs.enabled = true
        control.sync()
        installed = false
        packageEvent(Intent.ACTION_PACKAGE_REMOVED)
        control.sync()
        assertEquals(listOf(R.string.unison_status_missing), messages)
    }

    private fun packageEvent(action: String) {
        context.sendBroadcast(Intent(action, "package:${BridgeContract.PACKAGE}".toUri()))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
