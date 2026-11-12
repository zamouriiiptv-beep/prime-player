package com.castivio.playback.vlc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How LibVLC is let go of without holding the screen.
 *
 * ## The failure this file exists for
 *
 * Stopping or releasing a LibVLC player waits for the thread that reads the stream, and for a source that never played
 * that thread ends when its network read times out. The engine did it on the main thread, each time a channel was left,
 * so a channel the backup engine could not open froze the application for as long as the connection took to give up:
 * no key was answered, the application could not be closed, and the system offered to close it. A player cannot be
 * built here — it needs a device and a native library — so what is held is the rule the engine now lets go by:
 *
 *  - the work is **not done on the caller's thread**;
 *  - the caller waits **for the grace and no longer**, however long the work takes;
 *  - the work is **done in the order it was given**, one at a time, because two threads inside one LibVLC player is how
 *    it is freed while something is still reading it;
 *  - and what it throws, **it keeps**.
 *
 * Time is not slept through: slow work waits on a latch the test opens, so the tests are as fast as the machine and
 * cannot be made to fail by a busy one.
 */
class BoundedTeardownTest {

    private val grace = 60L

    // -------------------------------------------------------------- not on the caller's thread

    @Test
    fun `the work is done on a thread of its own, named, and not the caller's`() {
        val teardown = BoundedTeardown(graceMs = 1_000, threadName = "castivio-test-teardown")
        val ranOn = AtomicReference<Thread>()

        teardown.run { ranOn.set(Thread.currentThread()) }

        val worker = ranOn.get()
        assertNotEquals(Thread.currentThread(), worker)
        assertEquals("castivio-test-teardown", worker.name)
        // A teardown that is stuck on a dead connection must not keep the process alive.
        assertTrue(worker.isDaemon)
        teardown.close()
    }

    // -------------------------------------------------------------- the common case: quick work is finished

    @Test
    fun `quick work is finished by the time the caller is given its turn back`() {
        val teardown = BoundedTeardown(graceMs = 1_000, threadName = "castivio-test-teardown")
        val done = AtomicInteger(0)

        val finished = teardown.run { done.incrementAndGet() }

        assertTrue("what was quick has to be reported as finished", finished)
        // What the work did is visible: a decoder let go in it is let go when the next channel asks for one.
        assertEquals(1, done.get())
        teardown.close()
    }

    // -------------------------------------------------------------- the uncommon case: slow work is not waited for

    @Test
    fun `slow work does not hold the caller past the grace`() {
        val teardown = BoundedTeardown(graceMs = grace, threadName = "castivio-test-teardown")
        val release = CountDownLatch(1)
        val finishedWork = CountDownLatch(1)

        val startedAt = System.nanoTime()
        val finished = teardown.run {
            release.await() // a read that gives up when the network says so
            finishedWork.countDown()
        }
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertFalse("the work was still running", finished)
        assertTrue("waited the grace: $waitedMs ms", waitedMs >= grace - 5)
        // The ceiling is loose on purpose: the point is "the grace", not "the work", which here is unbounded.
        assertTrue("waited for the work and not the grace: $waitedMs ms", waitedMs < 5_000)

        // And the work was not abandoned: it carries on, and finishes, in the background.
        assertEquals(1L, finishedWork.count)
        release.countDown()
        assertTrue("the work has to finish once what it waits for arrives", finishedWork.await(5, TimeUnit.SECONDS))
        teardown.close()
    }

    @Test
    fun `no grace at all returns at once, whatever the work`() {
        val teardown = BoundedTeardown(graceMs = 0, threadName = "castivio-test-teardown")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)

        val finished = teardown.run {
            started.countDown()
            release.await()
        }

        assertFalse(finished)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        release.countDown()
        teardown.close()
    }

    // -------------------------------------------------------------- in order, one at a time

    @Test
    fun `work is done in the order it was given, and one at a time`() {
        val teardown = BoundedTeardown(graceMs = grace, threadName = "castivio-test-teardown")
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val running = AtomicInteger(0)
        val mostAtOnce = AtomicInteger(0)
        val gate = CountDownLatch(1)
        val allDone = CountDownLatch(3)

        fun step(name: String, wait: Boolean) = {
            val now = running.incrementAndGet()
            mostAtOnce.updateAndGet { maxOf(it, now) }
            if (wait) gate.await()
            order.add(name)
            running.decrementAndGet()
            allDone.countDown()
        }

        // The first is slow, so the second and the third are asked while it is still going: the caller is let go of
        // each time, and neither of them may start in front of it.
        assertFalse(teardown.run(step("stop", wait = true)))
        teardown.run(step("media", wait = false))
        teardown.run(step("player", wait = false))

        assertEquals("nothing may overtake the first", emptyList<String>(), order.toList())
        gate.countDown()
        assertTrue(allDone.await(5, TimeUnit.SECONDS))

        assertEquals(listOf("stop", "media", "player"), order.toList())
        assertEquals("two threads inside one player is how it is freed while it is read", 1, mostAtOnce.get())
        teardown.close()
    }

    // -------------------------------------------------------------- what it throws, it keeps

    @Test
    fun `a failure in the work stays with it and the next work still runs`() {
        val teardown = BoundedTeardown(graceMs = 1_000, threadName = "castivio-test-teardown")
        val next = AtomicInteger(0)

        // What a thread does with an exception nobody caught is end the process, on a device. The test's own handler
        // is how "nobody caught it" is seen: the JVM only prints it, and a test that passes over a printed stack
        // trace has proved nothing.
        val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        try {
            // An exception, and a linkage error, which is what a missing native library throws and what `runCatching`
            // around every step of the old teardown used to swallow.
            val first = teardown.run { throw IllegalStateException("the player was already released") }
            val second = teardown.run { throw UnsatisfiedLinkError("libvlcjni.so") }
            val third = teardown.run { next.incrementAndGet() }

            assertTrue("a failed teardown is a finished one", first)
            assertTrue(second)
            assertTrue(third)
            assertEquals("the worker has to outlive what it was given", 1, next.get())
            assertEquals("nothing may reach the thread's own handler: $uncaught", emptyList<Throwable>(), uncaught.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            teardown.close()
        }
    }

    // -------------------------------------------------------------- closing

    @Test
    fun `closing lets what was given finish`() {
        val teardown = BoundedTeardown(graceMs = grace, threadName = "castivio-test-teardown")
        val release = CountDownLatch(1)
        val finishedWork = CountDownLatch(1)

        assertFalse(teardown.run { release.await(); finishedWork.countDown() })
        teardown.close()

        release.countDown()
        assertTrue(
            "closing is \"nothing more is coming\", not \"stop what you are doing\"",
            finishedWork.await(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `work asked for after closing is still done, where it was asked`() {
        val teardown = BoundedTeardown(graceMs = 1_000, threadName = "castivio-test-teardown")
        teardown.close()
        val ranOn = AtomicReference<Thread>()

        val finished = teardown.run { ranOn.set(Thread.currentThread()) }

        assertTrue(finished)
        assertEquals("a teardown that is owed is not dropped", Thread.currentThread(), ranOn.get())
    }

    // -------------------------------------------------------------- the caller

    @Test
    fun `a caller that is told to stop waiting stops, and keeps its flag`() {
        val teardown = BoundedTeardown(graceMs = 30_000, threadName = "castivio-test-teardown")
        val release = CountDownLatch(1)

        Thread.currentThread().interrupt()
        val startedAt = System.nanoTime()
        val finished = teardown.run { release.await() }
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        try {
            assertFalse(finished)
            assertTrue("an interrupted wait is not a wait: $waitedMs ms", waitedMs < 5_000)
            assertTrue("whoever looks next has to be able to see it", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // leave the test thread as it was found
            release.countDown()
            teardown.close()
        }
    }

    @Test
    fun `an idle worker lets its thread go`() {
        val teardown = BoundedTeardown(graceMs = 1_000, threadName = "castivio-test-idle-worker", idleMs = 50)
        teardown.run { }

        // The thread leaves on its own, shortly after the idle time. Polled, not slept for a fixed time.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var alive = true
        while (alive && System.nanoTime() < deadline) {
            alive = Thread.getAllStackTraces().keys.any { it.name == "castivio-test-idle-worker" && it.isAlive }
            if (alive) Thread.sleep(20)
        }
        assertFalse("an idle worker leaves a thread behind", alive)
        teardown.close()
    }
}
