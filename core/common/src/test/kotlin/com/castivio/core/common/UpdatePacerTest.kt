package com.castivio.core.common

import com.castivio.core.common.UpdatePacer.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the screens are told, proved with a clock the test moves by hand. */
class UpdatePacerTest {

    private val pacer = UpdatePacer(intervalMs = 1_000)

    @Test
    fun `the first change is told at once`() {
        assertEquals(Step.Now, pacer.changed(5_000))
    }

    @Test
    fun `changes inside the interval wait for one timer, set to the end of it`() {
        pacer.changed(5_000)

        assertEquals(Step.Later(afterMs = 600), pacer.changed(5_400))
        assertEquals("a timer is already set", Step.Wait, pacer.changed(5_500))
        assertEquals(Step.Wait, pacer.changed(5_900))
    }

    @Test
    fun `the timer tells everything that changed since, once`() {
        pacer.changed(5_000)
        pacer.changed(5_400)
        pacer.changed(5_900)

        assertEquals(Step.Now, pacer.due(6_000))
        assertEquals("nothing left to tell", Step.Wait, pacer.due(6_001))
    }

    @Test
    fun `a change after the interval is told at once`() {
        pacer.changed(5_000)

        assertEquals(Step.Now, pacer.changed(6_000))
    }

    @Test
    fun `a timer that fires early asks for the rest`() {
        pacer.changed(5_000)
        pacer.changed(5_100)

        assertEquals(Step.Later(afterMs = 200), pacer.due(5_800))
        assertEquals(Step.Now, pacer.due(6_000))
    }

    /** Seven commits a second for ten seconds are told eleven times at most, and the last is not lost. */
    @Test
    fun `a long run is told about once per interval and ends told`() {
        var told = 0
        var timer: Long? = null
        var now = 0L
        fun act(step: Step) {
            when (step) {
                Step.Now -> told++
                is Step.Later -> timer = now + step.afterMs
                Step.Wait -> Unit
            }
        }
        while (now <= 10_000) {
            val due = timer
            if (due != null && due <= now) {
                timer = null
                act(pacer.due(now))
            }
            act(pacer.changed(now))
            now += 143
        }
        timer?.let { now = it; act(pacer.due(now)) }

        assertEquals(true, told in 10..12)
        assertEquals("nothing is left untold", Step.Wait, pacer.urgent(now))
    }

    @Test
    fun `urgent tells what is waiting now, and nothing when all was told`() {
        pacer.changed(5_000)
        pacer.changed(5_100)

        assertEquals(Step.Now, pacer.urgent(5_200))
        assertEquals(Step.Wait, pacer.urgent(5_300))
    }

    @Test
    fun `reset makes the next change immediate again`() {
        pacer.changed(5_000)
        pacer.changed(5_100)
        pacer.reset()

        assertEquals(Step.Now, pacer.changed(5_200))
    }
}
