package com.castivio.feature.player

import android.os.SystemClock
import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * **Temporary, for development.** The last few openings of a channel, and the lines the panel over the big player
 * draws from them -- see [OpeningReading] for what each figure is.
 *
 * Off unless [enable] was told so, and the player route tells it only in a debug build. Off, [PlayerViewModel]
 * takes no reading: nothing here runs, no frame callback is posted, and the player does what it did without it. Kept
 * outside the view model, and for the life of the process, so a viewer who leaves the player and presses another
 * channel still has the table of the openings before.
 *
 * The lines are made here, when a reading is published, and not in the composable that draws them: the panel
 * redraws ten times a second while a channel opens, and formatting is not work for composition.
 *
 * Removing it is deleting this file, [OpeningReading], `OpeningPanel`, the `diagnose` calls in the view model, and the
 * engines' two extra marks.
 */
internal object OpeningDiagnostics {

    /** How many finished openings the table keeps: enough to set a reused engine beside a rebuilt one. */
    const val KEEP = 8

    @Volatile
    var enabled: Boolean = false
        private set

    fun enable(on: Boolean) {
        enabled = on
    }

    /** Forgets every reading: for a test, which begins from none. */
    fun reset() {
        ids = 0
        _current.value = null
        _recent.value = emptyList()
        _panel.value = emptyList()
    }

    private var ids = 0

    /** Told to the view model for each opening it begins. Called on the main thread, where the view model is. */
    @Synchronized
    fun nextId(): Int = ++ids

    private val _current = MutableStateFlow<OpeningReading?>(null)

    /** The opening in progress, or the last one that ended. */
    val current: StateFlow<OpeningReading?> = _current.asStateFlow()

    private val _recent = MutableStateFlow<List<OpeningReading>>(emptyList())

    /** The openings that have ended, newest first, at most [KEEP]. */
    val recent: StateFlow<List<OpeningReading>> = _recent.asStateFlow()

    private val _panel = MutableStateFlow<List<HudLine>>(emptyList())

    /** What the panel draws: the current opening, then the table. Empty until there has been one. */
    val panel: StateFlow<List<HudLine>> = _panel.asStateFlow()

    /** A reading of an opening, as it stands. One that has ended is kept for the table. */
    fun publish(reading: OpeningReading) {
        _current.value = reading
        if (!reading.running) remember(reading)
        draw()
    }

    /** An opening that another press cut off before it ended: it is in the table as that, never as a success. */
    fun replaced(reading: OpeningReading) {
        remember(reading.copy(status = OpeningStatus.REPLACED))
        draw()
    }

    private fun remember(reading: OpeningReading) {
        _recent.update { all -> (listOf(reading) + all.filterNot { it.id == reading.id }).take(KEEP) }
    }

    private fun draw() {
        val now = _current.value ?: return
        val table = _recent.value
        _panel.value = buildList {
            addAll(now.lines())
            if (table.isNotEmpty()) {
                add(HudLine("last ${table.size}:", HudTone.MUTED))
                table.forEach { add(HudLine(it.row(), it.rowTone())) }
            }
        }
    }
}

/**
 * **Temporary, for development.** Counts the stretches during an opening in which the main thread drew no frame for
 * over [STALL_MS]: the time something held it -- a lock, a decoder let go, work that belongs elsewhere.
 *
 * A frame callback is posted for every frame while it runs, so the display is asked for a frame every refresh and a
 * gap between two callbacks is the main thread's, not an idle screen's. Started when an opening begins and stopped
 * when it ends, and never otherwise; it sees nothing before the engine is asked, which `press→open` in the reading
 * covers from the other side. Always on the main thread, where the view model is.
 */
internal class MainThreadWatch(private val thresholdMs: Long = STALL_MS) {

    private var running = false
    private var lastNanos = 0L
    private var count = 0
    private var maxMs = 0L
    private var totalMs = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.elapsedRealtimeNanos()
            val gapMs = (now - lastNanos) / NANOS_PER_MS
            if (gapMs > thresholdMs) {
                count++
                maxMs = maxOf(maxMs, gapMs)
                totalMs += gapMs
            }
            lastNanos = now
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Begins counting from nothing. */
    fun start() {
        stop()
        count = 0
        maxMs = 0
        totalMs = 0
        lastNanos = SystemClock.elapsedRealtimeNanos()
        running = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    /** What has been counted since [start]. */
    fun reading(): MainThreadStalls = MainThreadStalls(count, maxMs, totalMs)

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
