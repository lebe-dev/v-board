package ru.tinyops.android.vboard.voice.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One microphone and its FIFO of PCM windows, handed from the tap to recognition. Taken from Govorun. */
internal class MicrophoneCapture private constructor(
    val capture: BufferedPcmCapture,
    private val reading: AtomicBoolean,
    private val stopDevice: () -> Unit,
    private val releaseDevice: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    /** Stops reading: the frames already captured still reach the consumer. */
    fun stop() {
        if (reading.getAndSet(false)) runCatching { stopDevice() }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        capture.job.cancel()
        withContext(NonCancellable) {
            capture.job.join()
            capture.frames.cancel()
            releaseDevice()
        }
    }

    companion object {
        /** Starts capturing right away; null without the permission or a usable microphone. */
        fun open(
            context: Context,
            scope: CoroutineScope,
            headset: Boolean,
        ): MicrophoneCapture? {
            if (!scope.isActive) return null
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            val bytes = VadRecorder.VAD_WINDOW_SIZE * 2
            val reading = AtomicBoolean(true)
            val route = runCatching { RecordingMicrophone(context, headset) }.getOrNull() ?: return null
            val reader =
                MicrophoneReader(
                    open = { route.open(bytes) { !reading.get() || !scope.isActive } },
                    accepts = route::accepts,
                    fallback = route::recover,
                )
            return MicrophoneCapture(
                BufferedPcmCapture(scope, bytes, reading::get, reader::read),
                reading,
                reader::stop,
            ) {
                try {
                    reader.close()
                } finally {
                    route.close()
                }
            }
        }

        @SuppressLint("MissingPermission") // Checked in open() before any device is created.
        fun openDevice(
            bytes: Int,
            input: AudioDeviceInfo,
        ): AudioRecord? {
            val minimum =
                AudioRecord.getMinBufferSize(
                    VadRecorder.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            if (minimum <= 0) return null
            val device =
                runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        VadRecorder.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minimum.coerceAtLeast(bytes * DEVICE_BUFFER_WINDOWS),
                    )
                }.getOrNull() ?: return null
            // The device can disappear or Android can refuse it at any step.
            val started =
                runCatching {
                    device.state == AudioRecord.STATE_INITIALIZED &&
                        device.setPreferredDevice(input) &&
                        device.startRecording().let { device.recordingState == AudioRecord.RECORDSTATE_RECORDING }
                }.getOrDefault(false)
            if (started) return device
            device.release()
            return null
        }

        private const val DEVICE_BUFFER_WINDOWS = 8
    }
}

/**
 * Reads the microphone immediately while the VAD and the model load. The bounded FIFO keeps the
 * oldest sound; processing can never delay microphone reads or reuse a mutable frame.
 */
internal class BufferedPcmCapture(
    scope: CoroutineScope,
    windowBytes: Int,
    keepReading: () -> Boolean,
    read: (ByteArray, Int, Int) -> Int,
) {
    val frames = Channel<ByteArray>(Channel.BUFFERED)
    val job =
        scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                var complete = true
                while (complete && isActive && keepReading()) {
                    val frame = ByteArray(windowBytes)
                    var offset = 0
                    while (offset < frame.size && isActive && keepReading()) {
                        val count = read(frame, offset, frame.size - offset)
                        if (count <= 0) break
                        offset += count
                    }
                    if (offset > 0) frames.send(if (offset == frame.size) frame else frame.copyOf(offset))
                    complete = offset == frame.size
                }
            } finally {
                frames.close()
            }
        }
}

/** Device handoff of the capture worker. Stop can interrupt an open or a read; close follows the worker. */
internal class MicrophoneReader(
    private val open: () -> AudioRecord?,
    private val accepts: (AudioRecord) -> Boolean,
    private val fallback: () -> Unit,
) {
    private val device = AtomicReference<AudioRecord?>(null)

    @Volatile private var stopped = false

    fun read(
        target: ByteArray,
        offset: Int,
        count: Int,
    ): Int {
        repeat(2) {
            if (stopped) return 0
            val current = device.get() ?: (open() ?: return 0).also(device::set)
            if (stopped) {
                release()
                return 0
            }
            val read = current.read(target, offset, count)
            if (stopped) return 0
            if (read > 0 && accepts(current)) return read
            // Drop the stale buffer; the route owner tells a handoff from a failed input.
            release()
            fallback()
        }
        return 0
    }

    fun stop() {
        stopped = true
        device.get()?.let { runCatching { it.stop() } }
    }

    fun close() {
        stop()
        release()
    }

    private fun release() {
        device.getAndSet(null)?.let {
            runCatching { it.stop() }
            it.release()
        }
    }
}
