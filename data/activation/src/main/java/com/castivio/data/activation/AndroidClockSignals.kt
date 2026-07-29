package com.castivio.data.activation

import android.os.SystemClock
import com.castivio.domain.time.ClockSignalSource
import com.castivio.domain.time.ClockSignals
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three time signals, read from Android.
 *
 * Nothing here decides anything — that is [com.castivio.domain.time.MonotonicClock]'s
 * job, and it is pure precisely so that the awkward cases are unit tests rather than
 * things one hopes about a television.
 *
 * The boot identifier is the only part that needed thought. `elapsedRealtime` measures
 * time since boot, so a stored reading is only comparable to a live one when both were
 * taken during the same boot — otherwise a device that was switched off for a week
 * looks identical to one that has been awake for an hour. The kernel publishes a UUID
 * regenerated on every boot at `/proc/sys/kernel/random/boot_id`, world-readable and
 * needing no permission, which answers the question exactly.
 *
 * Where a hardened ROM hides that file, the fallback is a value minted once per
 * process. That is deliberately the conservative direction: it makes anchors expire at
 * every process restart instead of every reboot, which costs some precision and cannot
 * make the clock wrong — a lost anchor falls back to the device clock floored at the
 * high-water mark, never to a projection built on the wrong boot.
 */
@Singleton
class AndroidClockSignals @Inject constructor() : ClockSignalSource {

    private val bootId: String by lazy { readBootId() ?: PROCESS_ID }

    override fun read(): ClockSignals = ClockSignals(
        wallClockMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        bootId = bootId,
    )

    private fun readBootId(): String? = runCatching {
        File(BOOT_ID).takeIf { it.canRead() }?.readText()?.trim()?.ifEmpty { null }
    }.getOrNull()

    private companion object {
        const val BOOT_ID = "/proc/sys/kernel/random/boot_id"

        /** One per process, which is the safe reading when the kernel will not say. */
        val PROCESS_ID: String = UUID.randomUUID().toString()
    }
}
