package ru.tinyops.android.vboard.voice.engine

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneChoiceTest {
    private val phone = MicrophoneDevice(1, AudioDeviceInfo.TYPE_BUILTIN_MIC)
    private val wired = MicrophoneDevice(2, AudioDeviceInfo.TYPE_WIRED_HEADSET)
    private val bluetooth = MicrophoneDevice(3, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    private val usb = MicrophoneDevice(4, AudioDeviceInfo.TYPE_USB_HEADSET)
    private val a2dp = MicrophoneDevice(5, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    @Test
    fun `the best headset is chosen when headsets are allowed`() {
        val choice = MicrophoneChoice(headset = true, available = listOf(phone, bluetooth, wired))

        assertEquals(wired, choice.current(listOf(phone, bluetooth, wired)))
    }

    @Test
    fun `only the phone microphone is used when headsets are not allowed`() {
        val choice = MicrophoneChoice(headset = false, available = listOf(usb, phone, wired))

        assertEquals(phone, choice.current(listOf(usb, phone, wired)))
    }

    @Test
    fun `inputs that are not microphones are never chosen`() {
        val choice = MicrophoneChoice(headset = true, available = listOf(a2dp))

        assertNull(choice.current(listOf(a2dp)))
        assertEquals(phone, choice.current(listOf(a2dp, phone)))
    }

    @Test
    fun `a working headset is kept when a better one connects`() {
        val choice = MicrophoneChoice(headset = true, available = listOf(phone, bluetooth))

        assertEquals(bluetooth, choice.current(listOf(phone, bluetooth, usb)))
    }

    @Test
    fun `the phone gives way to a headset that connects during the recording`() {
        val choice = MicrophoneChoice(headset = true, available = listOf(phone))

        assertEquals(wired, choice.current(listOf(phone, wired)))
    }

    @Test
    fun `a headset that disappears for a moment comes back`() {
        val choice = MicrophoneChoice(headset = true, available = listOf(phone, bluetooth))

        assertEquals(phone, choice.current(listOf(phone)))
        assertEquals(bluetooth, choice.current(listOf(phone, bluetooth)))
    }

    @Test
    fun `a failed headset falls back to the phone for the rest of the recording`() {
        val available = listOf(phone, bluetooth)
        val choice = MicrophoneChoice(headset = true, available = available)

        assertEquals(phone, choice.fallback(available))
        assertEquals(phone, choice.current(available))
    }

    @Test
    fun `a failed headset is tried again after it reconnects`() {
        val choice = MicrophoneChoice(headset = true, available = listOf(phone, bluetooth))
        choice.fallback(listOf(phone, bluetooth))

        choice.current(listOf(phone))

        assertEquals(bluetooth, choice.current(listOf(phone, bluetooth)))
    }

    @Test
    fun `retry clears the failure of a headset`() {
        val available = listOf(phone, bluetooth)
        val choice = MicrophoneChoice(headset = true, available = available)
        choice.fallback(available)

        choice.retry(bluetooth)

        assertEquals(bluetooth, choice.current(available))
    }

    @Test
    fun `a failing phone microphone is not excluded`() {
        val choice = MicrophoneChoice(headset = true, available = listOf(phone))

        assertEquals(phone, choice.fallback(listOf(phone)))
    }

    @Test
    fun `recover keeps a route change and falls back when the active input failed`() {
        val choice = MicrophoneChoice(headset = true, available = listOf(phone, wired))

        assertEquals(wired, choice.recover(active = phone, available = listOf(phone, wired)))
        assertEquals(phone, choice.recover(active = wired, available = listOf(phone, wired)))
    }

    @Test
    fun `among devices of one type the lowest id wins`() {
        val second = MicrophoneDevice(9, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        val choice = MicrophoneChoice(headset = true, available = listOf(second, wired))

        assertEquals(wired, choice.current(listOf(second, wired)))
    }

    @Test
    fun `bluetooth microphones are usable only outside a call`() {
        val normal = AudioManager.MODE_NORMAL
        val call = AudioManager.MODE_IN_COMMUNICATION

        assertTrue(MicrophoneChoice.usable(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, normal))
        assertFalse(MicrophoneChoice.usable(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, call))
        assertFalse(MicrophoneChoice.usable(AudioDeviceInfo.TYPE_BLE_HEADSET, call))
        assertTrue(MicrophoneChoice.usable(AudioDeviceInfo.TYPE_WIRED_HEADSET, call))
        assertFalse(MicrophoneChoice.usable(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, normal))
    }

    @Test
    fun `communication is cleared once and only when it was taken`() {
        var cleared = 0
        var accept = false
        val communication = MicrophoneCommunication<Int>(set = { accept }, clear = { cleared++ })

        assertFalse(communication.request(1))
        communication.close()
        assertEquals(0, cleared)

        accept = true
        assertTrue(communication.request(1))
        assertTrue(communication.owned)
        communication.close()
        communication.close()
        assertEquals(1, cleared)
        assertFalse(communication.owned)
    }
}
