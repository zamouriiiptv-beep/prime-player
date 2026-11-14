package com.castivio.tv.probe.run

import com.castivio.tv.probe.core.InvalidReason
import com.castivio.tv.probe.core.NetworkSnapshot
import com.castivio.tv.probe.core.ProbeConfig
import com.castivio.tv.probe.core.ProbeEnvironment
import com.castivio.tv.probe.core.RunProgress

/**
 * Watches a run from beside it: samples what OkHttp itself says is running, ends the run when it must end, and tells the screen where it is.
 *
 * One thread per run, woken every bucket. What it samples is a second opinion on the connections, apart from
 * the bytes counted by the readers: the calls the dispatcher says are running, and the connections its pool holds.
 * It also looks at the network every second and at the clock for the cap, and it is where a stop from the
 * session becomes a cancellation of every request in flight.
 */
internal class RunMonitor(
    private val run: RunState,
    private val environment: ProbeEnvironment,
    private val config: ProbeConfig,
) {
    @Volatile
    var peakRunningCalls: Int = 0
        private set

    @Volatile
    var peakPoolConnections: Int = 0
        private set

    @Volatile
    var networkChanged: Boolean = false
        private set

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var initial: NetworkSnapshot? = null

    fun start(initialNetwork: NetworkSnapshot) {
        initial = initialNetwork
        running = true
        val monitor = Thread({ loop() }, "probe-monitor")
        monitor.isDaemon = true
        thread = monitor
        monitor.start()
    }

    fun stop() {
        running = false
        val monitor = thread ?: return
        monitor.interrupt()
        monitor.join(JOIN_MS)
    }

    private fun loop() {
        var tick = 0
        while (running) {
            sample(tick)
            tick++
            try {
                Thread.sleep(config.bucketMs)
            } catch (interrupted: InterruptedException) {
                break
            }
        }
        sample(tick)
    }

    private fun sample(tick: Int) {
        val calls = run.client.dispatcher.runningCallsCount()
        if (calls > peakRunningCalls) peakRunningCalls = calls
        val pooled = run.client.connectionPool.connectionCount()
        if (pooled > peakPoolConnections) peakPoolConnections = pooled
        if (run.request.control.cancelled) run.cancelAll(InvalidReason.CANCELLED)
        if (run.nowMs() > run.request.limits.capMs) run.cancelAll(InvalidReason.CAP_EXCEEDED)
        if (tick % PROGRESS_EVERY == 0) publish()
        val first = initial
        if (first != null && !networkChanged && tick % NETWORK_EVERY == 0) {
            if (!first.sameNetworkAs(environment.network())) networkChanged = true
        }
    }

    private fun publish() {
        try {
            run.request.progress(
                RunProgress(
                    label = run.request.step.label,
                    elapsedMs = run.nowMs(),
                    bytes = run.bytes.get(),
                    requestsDone = run.requestCount(),
                    inFlight = run.inFlightCount(),
                ),
            )
        } catch (ignored: Exception) {
            // The screen's own failure is not the run's.
        }
    }

    private companion object {
        const val PROGRESS_EVERY = 2
        const val NETWORK_EVERY = 4
        const val JOIN_MS = 1_000L
    }
}
