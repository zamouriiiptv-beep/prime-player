package com.castivio.core.platform

import java.util.Locale

/** One thread's scheduler accounts, in nanoseconds: how long it ran on a processor, and how long it waited on a run queue for one. */
data class SchedStat(val runNanos: Long, val waitNanos: Long)

/** What a process asked of its storage: bytes that came from or reached the device, and the calls that moved bytes at all. */
data class ProcIo(val readBytes: Long, val writeBytes: Long, val readCalls: Long, val writeCalls: Long)

/**
 * The kernel's `/proc` texts read into figures, and the figures into the words a strip says them in. Nothing here touches a
 * file: [ProcStats] reads, and a text it could not read is simply not here.
 *
 * Development builds only, by whoever calls it.
 */
object ProcParsers {

    /**
     * `/proc/<pid>/task/<tid>/schedstat`: `run_time run_delay timeslices`, the first two in nanoseconds. A thread that
     * waited for a processor a long time was runnable and not running -- the device was busy -- and one that was neither
     * running nor waiting was asleep: on a lock, a file, a socket.
     */
    fun schedStat(text: String): SchedStat? {
        val fields = text.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (fields.size < 2) return null
        val run = fields[0].toLongOrNull() ?: return null
        val wait = fields[1].toLongOrNull() ?: return null
        if (run < 0 || wait < 0) return null
        return SchedStat(runNanos = run, waitNanos = wait)
    }

    /**
     * `/proc/<pid>/io`: `name: value` lines. `read_bytes` and `write_bytes` are what reached or came from the storage
     * (a read the page cache answered is not in them); `syscr` and `syscw` count the calls.
     */
    fun io(text: String): ProcIo? {
        val values = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val value = line.substring(colon + 1).trim().toLongOrNull() ?: continue
            values[line.substring(0, colon).trim()] = value
        }
        val read = values["read_bytes"] ?: return null
        val written = values["write_bytes"] ?: return null
        return ProcIo(readBytes = read, writeBytes = written, readCalls = values["syscr"] ?: 0L, writeCalls = values["syscw"] ?: 0L)
    }

    /** What a process did to its storage between two readings: `read 312 MB (4,100 calls) · written 801 MB (190,000 calls)`. */
    fun describe(before: ProcIo, after: ProcIo): String =
        "read ${megabytes(after.readBytes - before.readBytes)} (${group(after.readCalls - before.readCalls)} calls)" +
            " · written ${megabytes(after.writeBytes - before.writeBytes)} (${group(after.writeCalls - before.writeCalls)} calls)"

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.0f MB", bytes.coerceAtLeast(0L) / 1_000_000.0)

    private fun group(count: Long): String = String.format(Locale.ROOT, "%,d", count.coerceAtLeast(0L))
}
