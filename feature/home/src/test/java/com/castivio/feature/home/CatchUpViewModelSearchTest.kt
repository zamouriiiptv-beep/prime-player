package com.castivio.feature.home

import com.castivio.core.common.Outcome
import com.castivio.domain.CatchUpChannel
import com.castivio.domain.CatchUpGateway
import com.castivio.domain.CatchUpListing
import com.castivio.domain.CatchUpProgramme
import com.castivio.domain.time.TimeAnchorSource
import com.castivio.domain.time.TimeReading
import com.castivio.domain.time.TimeTrust
import com.castivio.domain.time.TrustedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The search box on a channel's programmes: what it holds, and the first Back being the search's -- the same two rules the
 * pages under it keep, so the three boxes of Catch-up behave as one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchUpViewModelSearchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Clock : TrustedTime {
        override fun now(): TimeReading = TimeReading(1_000L, TimeTrust.DEVICE)
        override fun anchor(epochMs: Long, source: TimeAnchorSource): TimeReading = now()
    }

    private class Gateway : CatchUpGateway {
        override suspend fun listing(channel: CatchUpChannel, nowMs: Long): CatchUpListing = error("not asked")
        override suspend fun playbackUrl(channel: CatchUpChannel, programme: CatchUpProgramme): Outcome<String> =
            error("not asked")
        override fun knownEmpty(mediaId: String, nowMs: Long): Boolean = false
    }

    private fun model() = CatchUpViewModel(Gateway(), FakeCatalogue(emptyList()), Clock())

    @Test
    fun `the box holds what was typed, as typed`() {
        val model = model()
        model.search("  TaNdem ")
        assertEquals("  TaNdem ", model.searchText.value)
    }

    @Test
    fun `back closes a search that holds something, and says so`() {
        val model = model()
        model.search("tandem")
        assertTrue(model.clearSearch())
        assertEquals("", model.searchText.value)
    }

    @Test
    fun `back with nothing typed is not the search's, so the screen closes`() {
        assertFalse(model().clearSearch())
    }

    @Test
    fun `leaving the screen clears what was typed`() {
        val model = model()
        model.search("tandem")
        model.close()
        assertEquals("", model.searchText.value)
    }
}
