package ru.tinyops.android.vboard

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * The disk work of a store, one task after another on [executor]. [close] lets the queued tasks finish and turns
 * away the ones that come later, so a callback that arrives after the keyboard is gone does not crash it, and the
 * thread of the store ends with the keyboard instead of staying for the life of the process.
 */
internal class DiskQueue(
    private val executor: Executor,
) {
    @Volatile private var closed = false

    fun execute(task: () -> Unit) {
        if (closed) return
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // Closed from another thread in the meantime: the task is turned away like any later one.
        }
    }

    fun close() {
        closed = true
        (executor as? ExecutorService)?.shutdown()
    }
}
