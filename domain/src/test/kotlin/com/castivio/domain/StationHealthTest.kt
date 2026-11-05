package com.castivio.domain

import com.castivio.core.common.PlayOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a station's listens do to its place: the rests, the unstable, the directory's word and the device's. */
class StationHealthTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_000 * day

    private fun station(
        url: String = "http://example.invalid/stream",
        resolved: String? = null,
        ok: Boolean? = true,
        checkedAt: Long? = now - day,
    ) = WorldStation(
        uuid = "u1", name = "Station", url = url, urlResolved = resolved, favicon = null, countryCode = "MA", country = "Morocco",
        tags = emptyList(), codec = "MP3", bitrate = 128, hls = false, serverCheckedOk = ok, serverCheckedAtMs = checkedAt,
        votes = 0, clickCount = 0, sslError = false,
    )

    // ---------------------------------------------------------------------------- what an outcome writes

    @Test
    fun `an inconclusive listen writes nothing`() {
        val before = StationRecord("u1", consecutiveFailures = 2, lastFailedAtMs = now - hour)

        assertEquals(before, StationHealth.after(before, "u1", PlayOutcome.INCONCLUSIVE, now))
        assertNull(StationHealth.after(null, "u1", PlayOutcome.INCONCLUSIVE, now))
    }

    @Test
    fun `a confirmed listen clears the failures and the unstable count`() {
        val before = StationRecord("u1", lastFailedAtMs = now - hour, consecutiveFailures = 3, unstableCount = 2, unstableSinceMs = now - hour)

        val after = StationHealth.after(before, "u1", PlayOutcome.CONFIRMED, now)!!

        assertEquals(now, after.lastOkAtMs)
        assertEquals(0, after.consecutiveFailures)
        assertEquals(0, after.unstableCount)
        assertNull(after.unstableSinceMs)
    }

    @Test
    fun `a failure counts and an unstable listen does not`() {
        val failed = StationHealth.after(null, "u1", PlayOutcome.FAILED, now)!!
        val unstable = StationHealth.after(null, "u1", PlayOutcome.UNSTABLE, now)!!

        assertEquals(1, failed.consecutiveFailures)
        assertEquals(now, failed.lastFailedAtMs)
        assertEquals(0, unstable.consecutiveFailures)
        assertNull(unstable.lastFailedAtMs)
        assertEquals(1, unstable.unstableCount)
    }

    @Test
    fun `unstable listens are counted within a day and start again after it`() {
        var record = StationHealth.after(null, "u1", PlayOutcome.UNSTABLE, now)
        record = StationHealth.after(record, "u1", PlayOutcome.UNSTABLE, now + hour)
        assertEquals(2, record!!.unstableCount)

        val next = StationHealth.after(record, "u1", PlayOutcome.UNSTABLE, now + day + hour)!!

        assertEquals(1, next.unstableCount)
        assertEquals(now + day + hour, next.unstableSinceMs)
    }

    // ------------------------------------------------------------------------------------ the rests

    @Test
    fun `the rests climb with the failures and stop at three days`() {
        val rests = (1..6).map { n ->
            val record = StationRecord("u1", lastFailedAtMs = 0L, consecutiveFailures = n)
            StationHealth.restEndsAtMs(record)
        }

        assertEquals(listOf(hour, 6 * hour, day, 3 * day, 3 * day, 3 * day), rests)
    }

    @Test
    fun `a station that never failed is not resting`() {
        assertNull(StationHealth.restEndsAtMs(null))
        assertNull(StationHealth.restEndsAtMs(StationRecord("u1", lastOkAtMs = now)))
    }

    @Test
    fun `a station that failed is out of the lists for the rest and back after it, for ever never`() {
        val failed = StationRecord("u1", lastFailedAtMs = now, consecutiveFailures = 1)

        assertEquals(Standing.RESTING, StationHealth.standing(station(), failed, now + hour - 1))
        assertEquals(Standing.NORMAL, StationHealth.standing(station(), failed, now + hour))
        val worst = StationRecord("u1", lastFailedAtMs = now, consecutiveFailures = 50)
        assertEquals(Standing.RESTING, StationHealth.standing(station(), worst, now + 3 * day - 1))
        assertEquals(Standing.NORMAL, StationHealth.standing(station(), worst, now + 3 * day))
    }

    @Test
    fun `an unstable station is never rested, only lowered after its third of the day`() {
        val twice = StationRecord("u1", unstableCount = 2, unstableSinceMs = now - hour)
        val thrice = StationRecord("u1", unstableCount = 3, unstableSinceMs = now - hour)
        val longAgo = StationRecord("u1", unstableCount = 5, unstableSinceMs = now - 2 * day)

        assertEquals(Standing.NORMAL, StationHealth.standing(station(), twice, now))
        assertEquals(Standing.LOWERED, StationHealth.standing(station(), thrice, now))
        assertEquals(Standing.NORMAL, StationHealth.standing(station(), longAgo, now))
    }

    // ------------------------------------------------------------- the directory's word and the device's

    @Test
    fun `an address the player cannot open is screened out`() {
        assertEquals(Standing.SCREENED_OUT, StationHealth.standing(station(url = "rtmp://example.invalid/live"), null, now))
        assertEquals(Standing.SCREENED_OUT, StationHealth.standing(station(url = "  "), null, now))
        assertEquals(Standing.NORMAL, StationHealth.standing(station(url = "rtmp://x", resolved = "https://example.invalid/s"), null, now))
    }

    @Test
    fun `the directory's own word that a stream does not play screens it out`() {
        assertEquals(Standing.SCREENED_OUT, StationHealth.standing(station(ok = false), null, now))
    }

    @Test
    fun `a listen confirmed here after the directory's check outranks it`() {
        val checked = now - 3 * day
        val vouched = StationRecord("u1", lastOkAtMs = now - hour)

        assertEquals(Standing.NORMAL, StationHealth.standing(station(ok = false, checkedAt = checked), vouched, now))
        assertEquals(Standing.SCREENED_OUT, StationHealth.standing(station(ok = false, checkedAt = now), vouched, now))
    }

    @Test
    fun `a check that is old or missing lowers a station, and a listen confirmed here since lifts it`() {
        val old = station(checkedAt = now - 40 * day)
        val unknown = station(ok = null, checkedAt = null)

        assertEquals(Standing.LOWERED, StationHealth.standing(old, null, now))
        assertEquals(Standing.LOWERED, StationHealth.standing(unknown, null, now))
        assertEquals(Standing.NORMAL, StationHealth.standing(old, StationRecord("u1", lastOkAtMs = now - hour), now))
    }

    @Test
    fun `lists show a station that is normal or lowered, and rank normal first`() {
        assertTrue(StationHealth.listed(Standing.NORMAL))
        assertTrue(StationHealth.listed(Standing.LOWERED))
        assertFalse(StationHealth.listed(Standing.RESTING))
        assertFalse(StationHealth.listed(Standing.SCREENED_OUT))
        assertTrue(StationHealth.rank(Standing.NORMAL) < StationHealth.rank(Standing.LOWERED))
        assertTrue(StationHealth.rank(Standing.LOWERED) < StationHealth.rank(Standing.RESTING))
    }

    // ------------------------------------------------------------------------------- the addresses

    @Test
    fun `the resolved address is played first and the submitted one is the other to try`() {
        val s = station(url = "http://a.invalid/x", resolved = "http://b.invalid/y")

        assertEquals("http://b.invalid/y", s.streamUrl)
        assertEquals("http://a.invalid/x", s.alternateUrl)
        assertNull(station(url = "http://a.invalid/x").alternateUrl)
        assertNull(station(url = "http://a.invalid/x", resolved = "http://a.invalid/x").alternateUrl)
    }

    // ----------------------------------------------------------------------------- the network guard

    @Test
    fun `one station failing is its own, three within two minutes are the network's`() {
        val guard = NetworkTroubleGuard()

        assertTrue(guard.countsAgainst("a", now))
        assertTrue(guard.countsAgainst("b", now + 10_000))
        assertFalse("the third different station is the network's", guard.countsAgainst("c", now + 20_000))
        assertTrue("a long time after, a station is its own again", guard.countsAgainst("d", now + 10 * 60_000))
    }

    @Test
    fun `the same station failing again is not three stations`() {
        val guard = NetworkTroubleGuard()

        assertTrue(guard.countsAgainst("a", now))
        assertTrue(guard.countsAgainst("a", now + 1_000))
        assertTrue(guard.countsAgainst("a", now + 2_000))
    }
}
