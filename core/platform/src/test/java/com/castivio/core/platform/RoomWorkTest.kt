package com.castivio.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a stack of one of Room's threads is named, and what a load's looks add up to. */
class RoomWorkTest {

    private fun frame(className: String, method: String) = StackFrame(className, method)

    private val dao = frame("com.castivio.data.database.dao.MediaDao_Impl\$7", "call")
    private val room = frame("androidx.room.CoroutinesRoom\$Companion\$execute\$2", "invokeSuspend")
    private val sqlite = frame("android.database.sqlite.SQLiteConnection", "nativeExecuteForCursorWindow")

    @Test
    fun `a running thread is named by the application's own frame nearest the work`() {
        val label = RoomWork.label(running = true, frames = listOf(sqlite, dao, room))

        assertEquals("MediaDao_Impl\$7.call", label)
    }

    @Test
    fun `of two of the application's frames the one nearer the work is the name`() {
        val outer = frame("com.castivio.data.database.WindowPagingSource", "load")
        val inner = frame("com.castivio.data.database.dao.MediaDao_Impl", "window")

        assertEquals("MediaDao_Impl.window", RoomWork.label(true, listOf(sqlite, inner, room, outer)))
    }

    @Test
    fun `a running thread with none of the application's frames is other work`() {
        assertEquals(RoomWork.OTHER, RoomWork.label(true, listOf(sqlite, room)))
        assertEquals(RoomWork.OTHER, RoomWork.label(true, emptyList()))
    }

    @Test
    fun `an idle thread is nothing and one waiting for the connection is said`() {
        val parked = frame("jdk.internal.misc.Unsafe", "park")
        val waiting = frame("android.database.sqlite.SQLiteConnectionPool", "waitForConnection")

        assertNull(RoomWork.label(running = false, frames = listOf(parked, room)))
        assertEquals(RoomWork.WAITING_FOR_CONNECTION, RoomWork.label(running = false, frames = listOf(parked, waiting, dao)))
    }

    @Test
    fun `a load's looks add up to shares of the pool's time and of its running time`() {
        val counts = mapOf(
            "MediaDao_Impl\$7.call" to 600,
            "WindowPagingSource.load" to 200,
            RoomWork.OTHER to 200,
            RoomWork.WAITING_FOR_CONNECTION to 100,
        )

        val text = RoomWork.summary(ticks = 1_000, threads = 5, counts = counts)

        assertEquals(
            "running 20% of 5 threads over 1000 looks · waiting for a connection 2%" +
                " · of the running: MediaDao_Impl\$7.call 60% · WindowPagingSource.load 20% · other 20%",
            text,
        )
    }

    @Test
    fun `the names are listed largest first and at most six`() {
        val counts = (1..9).associate { "name$it" to it * 10 }

        val text = RoomWork.summary(ticks = 100, threads = 1, counts = counts)!!

        val listed = text.substringAfter("of the running: ").split(" · ")
        assertEquals(6, listed.size)
        assertTrue(listed.first().startsWith("name9 "))
        assertTrue(listed.last().startsWith("name4 "))
    }

    @Test
    fun `a pool that was never seen says nothing, and one that never ran says only that`() {
        assertNull(RoomWork.summary(ticks = 0, threads = 0, counts = emptyMap()))
        assertEquals(
            "running 0% of 2 threads over 50 looks · waiting for a connection 0%",
            RoomWork.summary(ticks = 50, threads = 2, counts = emptyMap()),
        )
    }
}
