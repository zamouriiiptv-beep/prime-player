package com.castivio.feature.home

import com.castivio.core.common.format.CastivioDates
import com.castivio.domain.Channel
import com.castivio.domain.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the live card offers once something was watched, and which face the card shows
 * (`design/mockups/home-resume-ar.png` and `home-resume-idle-ar.png`).
 */
class HomeResumeTest {

    private val now = 1_800_000_000_000L

    private fun channel(id: String, guide: String? = null) = Channel(
        id = id,
        title = "FR| $id",
        artworkUrl = null,
        number = null,
        groupId = null,
        streamUrl = "http://provider.invalid/$id",
        epgChannelId = guide,
    )

    private fun programme(startOffsetMs: Long, stopOffsetMs: Long, title: String = "Le grand concours") = Programme(
        channelId = "tf1",
        title = title,
        description = null,
        startMs = now + startOffsetMs,
        stopMs = now + stopOffsetMs,
    )

    // ------------------------------------------------------------------------------------------ the offer

    @Test
    fun `nothing watched offers nothing`() {
        assertNull(resumeOf(emptyList(), programme(-1_000, 1_000), now))
    }

    @Test
    fun `the channel watched last is the one resumed, and the ones before it follow in order`() {
        val recent = listOf(channel("tf1"), channel("f2"), channel("f3"), channel("f4"), channel("f5"))

        val offer = resumeOf(recent, programme = null, nowMs = now)!!

        assertEquals("tf1", offer.channel.id)
        assertEquals(listOf("f2", "f3", "f4", "f5"), offer.earlier.map { it.id })
    }

    @Test
    fun `no more than four earlier channels are named, whatever is read`() {
        val recent = (1..9).map { channel("c$it") }

        val offer = resumeOf(recent, programme = null, nowMs = now)!!

        assertEquals(RESUME_EARLIER, offer.earlier.size)
        assertEquals(listOf("c2", "c3", "c4", "c5"), offer.earlier.map { it.id })
    }

    @Test
    fun `a channel watched alone has no earlier ones`() {
        assertEquals(emptyList<Channel>(), resumeOf(listOf(channel("tf1")), null, now)!!.earlier)
    }

    @Test
    fun `what is on is carried with how far in it is, and its hours as text`() {
        val on = programme(startOffsetMs = -3_600_000, stopOffsetMs = 1_200_000)

        val offer = resumeOf(listOf(channel("tf1")), on, now)!!

        assertEquals(on, offer.programme)
        assertEquals(on.progressAt(now), offer.progress, 0f)
        assertEquals(CastivioDates.time(on.startMs), offer.from)
        assertEquals(CastivioDates.time(on.stopMs), offer.to)
    }

    @Test
    fun `a channel the guide holds nothing for says no programme and no hours`() {
        val offer = resumeOf(listOf(channel("tf1")), programme = null, nowMs = now)!!

        assertNull(offer.programme)
        assertNull(offer.from)
        assertNull(offer.to)
        assertEquals(0f, offer.progress, 0f)
    }

    @Test
    fun `a programme that is not on now is not offered as what is on`() {
        val ended = programme(startOffsetMs = -7_200_000, stopOffsetMs = -60_000)
        val coming = programme(startOffsetMs = 60_000, stopOffsetMs = 3_600_000)

        assertNull(resumeOf(listOf(channel("tf1")), ended, now)!!.programme)
        assertNull(resumeOf(listOf(channel("tf1")), coming, now)!!.programme)
    }

    // ------------------------------------------------------------------------------------- the guide keys

    @Test
    fun `the guide id is tried before the media id, as the guide's writer files them`() {
        assertEquals(listOf("tf1.fr", "tf1"), guideKeys(channel("tf1", guide = "tf1.fr")))
    }

    @Test
    fun `a channel with no guide id, or a blank one, is looked up by its media id`() {
        assertEquals(listOf("tf1"), guideKeys(channel("tf1", guide = null)))
        assertEquals(listOf("tf1"), guideKeys(channel("tf1", guide = "  ")))
    }

    @Test
    fun `a guide id that is the media id is not asked twice`() {
        assertEquals(listOf("tf1"), guideKeys(channel("tf1", guide = "tf1")))
    }

    // ------------------------------------------------------------------------------------ when it is read again

    @Test
    fun `a programme is read again once a minute while it is on`() {
        assertEquals(RESUME_TICK_MS, resumeRefreshMs(programme(-1_000, 3_600_000), now))
    }

    @Test
    fun `a programme that ends sooner than a tick is read again as it ends`() {
        assertEquals(20_000L + 1_000L, resumeRefreshMs(programme(-1_000, 20_000), now))
    }

    @Test
    fun `a programme that ends in the next millisecond is read again a second after it, not in a loop`() {
        assertEquals(1_001L, resumeRefreshMs(programme(-1_000, 1), now))
    }

    @Test
    fun `a programme that has just ended is treated as none until the guide is read again`() {
        assertEquals(RESUME_RETRY_MS, resumeRefreshMs(programme(-1_000, 0), now))
    }

    @Test
    fun `a channel with no programme is asked about again after a few minutes`() {
        assertEquals(RESUME_RETRY_MS, resumeRefreshMs(null, now))
        assertTrue(RESUME_RETRY_MS > RESUME_TICK_MS)
    }

    @Test
    fun `a programme that is not on is treated as none`() {
        assertEquals(RESUME_RETRY_MS, resumeRefreshMs(programme(-7_200_000, -60_000), now))
    }

    // ------------------------------------------------------------------------------------------ the face

    private val offer = resumeOf(listOf(channel("tf1")), null, now)

    @Test
    fun `a section never fetched and not on its way invites the viewer to load it`() {
        assertEquals(HeroFace.NotLoaded, heroFace(unfetched = true, counted = false, load = CardLoad.Idle, resume = null))
        // What was watched belongs to rows that are no longer here: nothing is resumed from a section that is not.
        assertEquals(HeroFace.NotLoaded, heroFace(unfetched = true, counted = false, load = CardLoad.Idle, resume = offer))
    }

    @Test
    fun `a section here with something watched offers to carry on`() {
        assertEquals(HeroFace.Continue, heroFace(unfetched = false, counted = true, load = CardLoad.Idle, resume = offer))
    }

    @Test
    fun `a section here with nothing watched is the card as it was`() {
        assertEquals(HeroFace.Plain, heroFace(unfetched = false, counted = true, load = CardLoad.Idle, resume = null))
    }

    @Test
    fun `a section fetched and found empty is the card as it was`() {
        assertEquals(HeroFace.Plain, heroFace(unfetched = false, counted = false, load = CardLoad.Idle, resume = offer))
    }

    @Test
    fun `every state of a load keeps the card as it was, whatever was watched`() {
        val loads = listOf(
            CardLoad.Waiting(),
            CardLoad.Waiting(hinted = true),
            CardLoad.Arriving(items = 0, percent = null, refresh = false),
            CardLoad.Arriving(items = 100, percent = 38, refresh = true),
            CardLoad.Arrived(items = 100, seconds = 12),
            CardLoad.Failed(refresh = false),
            CardLoad.Failed(refresh = true),
        )
        for (load in loads) {
            for (unfetched in listOf(true, false)) {
                assertEquals(
                    "$load unfetched=$unfetched",
                    HeroFace.Plain,
                    heroFace(unfetched = unfetched, counted = !unfetched, load = load, resume = offer),
                )
            }
        }
    }
}
