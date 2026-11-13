package com.castivio.core.platform

/**
 * Looks at the stacks of Room's pool threads while a load runs, a few times a second, and says what they were doing.
 *
 * Development builds only, started and stopped by the importer around one load. The threads are found by name (the pool
 * names them `arch_disk_io_N`) and looked for again every second, since the pool grows and shrinks. A look costs a
 * suspension of each thread for the length of reading its stack -- some tens of microseconds -- and a whole pass over the
 * process's threads once a second: no more than a measuring build can bear, and nothing at all in a release build, which
 * does not start it. Nothing here can fail a load: whatever goes wrong in a look is a look not taken.
 *
 * What a stack shows is named by [RoomWork].
 *
 * @param intervalMs the time between two looks.
 * @param poolNamePrefix what the pool's threads' names begin with.
 */
class RoomThreadSampler(
    private val intervalMs: Long = SAMPLE_INTERVAL_MS,
    private val poolNamePrefix: String = ROOM_POOL_PREFIX,
) {
    private val counts = HashMap<String, Int>()
    private var ticks = 0
    private var threads = 0

    @Volatile private var stopping = false
    private var worker: Thread? = null

    /** Begins looking. */
    fun start() {
        stopping = false
        val thread = Thread({ loop() }, "load-sampler")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        worker = thread
        thread.start()
    }

    /** Stops looking and says what was seen, or null when the pool was never there to be seen. */
    fun stop(): String? {
        stopping = true
        val thread = worker ?: return null
        thread.interrupt()
        try {
            thread.join(JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return synchronized(this) { RoomWork.summary(ticks, threads, HashMap(counts)) }
    }

    private fun loop() {
        var pool: List<Thread> = emptyList()
        var refreshedAt = 0L
        val giveUpAt = System.nanoTime() + MAX_NANOS
        while (!stopping) {
            val now = System.nanoTime()
            // A load that was never stopped, an import that ended some way nobody caught, is not looked at for ever.
            if (now >= giveUpAt) return
            if (pool.isEmpty() || now - refreshedAt >= REFRESH_NANOS) {
                pool = try {
                    Thread.getAllStackTraces().keys.filter { it.name.startsWith(poolNamePrefix) }
                } catch (e: Exception) {
                    emptyList()
                }
                refreshedAt = now
            }
            look(pool)
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun look(pool: List<Thread>) {
        if (pool.isEmpty()) return
        synchronized(this) {
            ticks++
            if (pool.size > threads) threads = pool.size
        }
        for (thread in pool) {
            val running = thread.state == Thread.State.RUNNABLE
            val frames = try {
                thread.stackTrace.map { StackFrame(it.className, it.methodName) }
            } catch (e: Exception) {
                continue
            }
            val label = RoomWork.label(running, frames) ?: continue
            synchronized(this) { counts[label] = (counts[label] ?: 0) + 1 }
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 100L
        const val ROOM_POOL_PREFIX = "arch_disk_io"
        const val REFRESH_NANOS = 1_000_000_000L
        const val JOIN_MS = 1_000L

        /** The longest one load is looked at: far past the longest a first load of a large library has run. */
        const val MAX_NANOS = 20L * 60L * 1_000_000_000L
    }
}
