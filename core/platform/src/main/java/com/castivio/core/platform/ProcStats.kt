package com.castivio.core.platform

import android.os.Process
import java.io.File

/**
 * Reads of this process's own scheduler and storage accounts, for the load strip of a development build.
 *
 * Both are `/proc` files a process may read about itself on most devices; where one cannot be read the answer is null and
 * the strip leaves its line out. Read twice per load, or once per batch of rows, never per row. See [ProcParsers] for what
 * the figures mean.
 */
object ProcStats {

    /** This thread's scheduler accounts now. */
    fun schedStatOfThisThread(): SchedStat? =
        readText("/proc/self/task/${Process.myTid()}/schedstat")?.let(ProcParsers::schedStat)

    /** This process's storage accounts now. */
    fun ioOfThisProcess(): ProcIo? = readText("/proc/self/io")?.let(ProcParsers::io)

    private fun readText(path: String): String? = try {
        File(path).readText()
    } catch (e: Exception) {
        null
    }
}
