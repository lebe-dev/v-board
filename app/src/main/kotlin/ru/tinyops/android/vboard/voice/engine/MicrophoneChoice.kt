package ru.tinyops.android.vboard.voice.engine

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** A session's stable device identity; a Bluetooth communication sink can represent its matching input. */
internal data class MicrophoneDevice(
    val id: Int,
    val type: Int,
) {
    val phone: Boolean get() = type == AudioDeviceInfo.TYPE_BUILTIN_MIC
}

/**
 * "Микрофон": keeps a working headset when [headset] is allowed; absence is temporary, while a
 * failed headset falls back to the phone for the rest of the recording. Taken from Govorun.
 */
internal class MicrophoneChoice(
    private val headset: Boolean,
    available: List<MicrophoneDevice>,
) {
    private var selected: MicrophoneDevice? = null
    private val failed = mutableSetOf<MicrophoneDevice>()

    init {
        current(available)
    }

    @Synchronized
    fun current(available: List<MicrophoneDevice>): MicrophoneDevice? {
        // A new connection is a new opportunity, even with the same device id.
        failed.retainAll(available.toSet())
        val candidates = available.filter { priority(it.type) != null && (headset || it.phone) && it !in failed }
        if (selected !in candidates || selected?.phone != false) {
            selected = candidates.minWithOrNull(compareBy({ priority(it.type) }, { it.id }))
        }
        return selected
    }

    @Synchronized
    fun fallback(available: List<MicrophoneDevice>): MicrophoneDevice? {
        selected?.takeUnless { it.phone }?.let { failed += it }
        selected = null
        return current(available)
    }

    @Synchronized
    fun retry(device: MicrophoneDevice) {
        failed.remove(device)
    }

    @Synchronized
    fun recover(
        active: MicrophoneDevice?,
        available: List<MicrophoneDevice>,
    ): MicrophoneDevice? = current(available).let { if (it == active) fallback(available) else it }

    companion object {
        private val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

        fun usable(
            type: Int,
            mode: Int,
        ): Boolean = priority(type) != null && (mode == AudioManager.MODE_NORMAL || type !in BLUETOOTH)

        /** Lower is preferred; null for inputs that are not microphones we select (A2DP, hearing aids...). */
        @Suppress("MagicNumber") // The priority order itself.
        fun priority(type: Int): Int? =
            when (type) {
                AudioDeviceInfo.TYPE_USB_HEADSET -> 0
                AudioDeviceInfo.TYPE_USB_DEVICE -> 1
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
                AudioDeviceInfo.TYPE_BLE_HEADSET -> 3
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> 5
                else -> null
            }
    }
}

/** Android drops communication requests on disconnection: every open must submit again. */
internal class MicrophoneCommunication<T>(
    private val set: (T) -> Boolean,
    private val clear: () -> Unit,
) {
    var owned = false
        private set

    @Synchronized
    fun request(device: T): Boolean = set(device).also { if (it) owned = true }

    @Synchronized
    fun close() {
        if (owned) {
            owned = false
            clear()
        }
    }
}
