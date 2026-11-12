package com.castivio.playback.vlc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Slow work that must not be done on the thread that asked for it, and that the asker can still wait for — a little.
 *
 * ## What this is for
 *
 * LibVLC 3 lets go of a source **synchronously**. Stopping a player, or releasing it, joins the thread that reads the
 * stream: the call returns when that thread has finished, and that thread is finished when the network says so. A
 * source that is playing usually lets go quickly. A source that never played — a server that answered and then said nothing, a
 * name that is still being resolved, a connection that is being retried — lets go when its read times out, and that is
 * seconds, not milliseconds. Called on the main thread, as the player does it each time a channel is left, those
 * seconds are a screen that does not answer: no key moves the focus, the application cannot be closed, and after five
 * of them the system offers to close it.
 *
 * LibVLC's own sources say as much where they build a surface for it: a thread of its own is made "in order to
 * don't use the MainLooper one (and have deadlock when we stop VLC from the mainloop)".
 *
 * ## What it does
 *
 * Runs the work on a thread of its own, in the order it was given, and lets the caller wait for it for [graceMs] and
 * no longer. The grace is the other half of the design. Most teardowns are quick, and a channel that is being left for
 * another one is better off if what it held — a hardware decoder, above all — has been let go before the next channel
 * asks for one, so the caller waits for the common case and is let go of in the uncommon one. A teardown that is not
 * done by then carries on in the background, and the caller carries on with it.
 *
 * Nothing here knows about LibVLC: it is a thread, a queue and a clock, so the rules above are asserted by an ordinary
 * JVM test, which is the only kind of test this engine's neighbourhood can have.
 *
 * @param graceMs how long [run] waits for its work before it returns without it.
 * @param threadName the worker's name, as `ps` and a stack dump will show it.
 * @param idleMs how long an idle worker's thread is kept before it is let go. The next work starts a new one.
 */
internal class BoundedTeardown(
    private val graceMs: Long,
    threadName: String,
    idleMs: Long = IDLE_MS,
) {

    private val worker = ThreadPoolExecutor(
        1, 1, idleMs, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        // A daemon: a teardown that is stuck on a dead connection must not be what keeps the process alive.
        ThreadFactory { task -> Thread(task, threadName).apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    /**
     * Runs [work] on the worker, after everything given before it, and waits up to the grace for it.
     *
     * Whatever [work] throws stays with it: a teardown that fails has nothing left to be told to, and a worker that
     * dies of it would leave the next one unrun.
     *
     * @return true when [work] had finished by the time this returned; false when it is still running, or waiting for
     *   the work in front of it, and the caller has been let go.
     */
    fun run(work: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        val task = Runnable {
            try {
                work()
            } catch (_: Throwable) {
                // See above.
            } finally {
                done.countDown()
            }
        }
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            // Asked after [close]: the worker is gone, and the work is still owed. Done where it was asked, which is
            // what every caller did before there was a worker.
            task.run()
            return true
        }
        return try {
            done.await(graceMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // The caller was told to stop waiting. It stops, and its flag says so to whoever looks next.
            Thread.currentThread().interrupt()
            false
        }
    }

    /** No more work is coming. What has been given still runs, and the thread goes when it is done. */
    fun close() {
        worker.shutdown()
    }

    companion object {
        /** How long an idle worker keeps its thread. Long enough for a retry, short enough to leave nothing behind. */
        const val IDLE_MS = 3_000L
    }
}
