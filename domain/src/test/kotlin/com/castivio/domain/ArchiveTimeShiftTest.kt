package com.castivio.domain

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a channel's rewind is told by the archive, and what it asks of it.
 *
 * The gateway is a fake that counts what it is asked: most of what matters here is that the channel is resolved once, and that
 * an address is made only for a place the archive still offers.
 */
class ArchiveTimeShiftTest {

    private val minute = CatchUpPolicy.MINUTE_MS
    private val base = 28_333_333L * minute
    private var now = base + 30_000L

    private val channel = CatchUpChannel(mediaId = "a", providerRef = "11", title = "Nova", archiveHours = 5)

    private fun span(startAgoMin: Double, stopAgoMin: Double, archived: Boolean = true) = ArchiveSpan(
        startMs = now - (startAgoMin * minute).toLong(),
        stopMs = now - (stopAgoMin * minute).toLong(),
        archived = archived,
    )

    /** An archive that reaches back five hours and has had the programme on air for forty minutes. */
    private fun deep() = ArchiveCover(
        spans = listOf(span(300.0, 40.0), span(40.0, -20.0, archived = false)),
        clockOffsetMs = 3_600_000L,
    )

    private class FakeGateway(var coverage: ArchiveCoverage, var url: Outcome<String> = Outcome.Success("http://panel/archive")) : CatchUpGateway {
        val coverageAsked = mutableListOf<Long>()
        class AddressAsked(val atMs: Long, val minutes: Int)
        val addressAsked = mutableListOf<AddressAsked>()

        override suspend fun listing(channel: CatchUpChannel, nowMs: Long): CatchUpListing = CatchUpListing.Programmes(emptyList())
        override suspend fun playbackUrl(channel: CatchUpChannel, programme: CatchUpProgramme): Outcome<String> = Outcome.Failure(AppError.NOT_FOUND)
        override fun knownEmpty(mediaId: String, nowMs: Long): Boolean = false

        override suspend fun coverage(channel: CatchUpChannel, nowMs: Long): ArchiveCoverage {
            coverageAsked += nowMs
            return coverage
        }

        override suspend fun playbackUrlAt(channel: CatchUpChannel, cover: ArchiveCover, atMs: Long, durationMinutes: Int): Outcome<String> {
            addressAsked += AddressAsked(atMs, durationMinutes)
            return url
        }
    }

    private class Resolver(private val result: CatchUpChannel?) {
        var calls = 0
        val resolve: suspend () -> CatchUpChannel? = { calls++; result }
    }

    private fun source(gateway: FakeGateway, resolver: Resolver = Resolver(channel)) =
        ArchiveTimeShift(gateway = gateway, channel = resolver.resolve, nowMs = { now })

    // ------------------------------------------------------------------------------------ the choices

    @Test
    fun `a channel whose archive is proved offers what the policy says it does`() = runBlocking {
        val gateway = FakeGateway(ArchiveCoverage.Known(deep()))

        val offered = source(gateway).choices()

        assertEquals(TimeShiftPolicy.choices(deep(), now, channel.archiveHours), offered)
        assertTrue(offered.isNotEmpty())
    }

    @Test
    fun `nothing is offered where the archive is not known, is not there, or did not answer`() = runBlocking {
        assertTrue(source(FakeGateway(ArchiveCoverage.None)).choices().isEmpty())
        assertTrue(source(FakeGateway(ArchiveCoverage.Failed(AppError.TIMEOUT))).choices().isEmpty())
    }

    @Test
    fun `a channel the catalogue cannot name is offered nothing and the archive is not asked`() = runBlocking {
        val gateway = FakeGateway(ArchiveCoverage.Known(deep()))

        assertTrue(source(gateway, Resolver(null)).choices().isEmpty())
        assertTrue(gateway.coverageAsked.isEmpty())
    }

    @Test
    fun `the channel is resolved once however often the archive is asked about it`() = runBlocking {
        val resolver = Resolver(channel)
        val rewind = source(FakeGateway(ArchiveCoverage.Known(deep())), resolver)

        rewind.choices()
        rewind.choices()
        rewind.address(TimeShiftChoice.MINUTES_15)

        assertEquals(1, resolver.calls)
    }

    @Test
    fun `a channel that could not be resolved is not resolved again`() = runBlocking {
        val resolver = Resolver(null)
        val rewind = source(FakeGateway(ArchiveCoverage.Known(deep())), resolver)

        rewind.choices()
        rewind.choices()

        assertEquals(1, resolver.calls)
    }

    // ------------------------------------------------------------------------------------ the address

    @Test
    fun `an address is made for the place the choice names, asked for from that minute and on past now`() = runBlocking {
        val gateway = FakeGateway(ArchiveCoverage.Known(deep()))

        val address = source(gateway).address(TimeShiftChoice.MINUTES_15)

        val asked = gateway.addressAsked.single()
        assertEquals(now - 15 * minute, asked.atMs)
        assertEquals(TimeShiftPolicy.durationMinutes(now - 15 * minute, now), asked.minutes)
        // The archive begins at the minute the place falls in, which is thirty seconds before it.
        assertEquals(Outcome.Success(TimeShiftAddress("http://panel/archive", behindMs = 15 * minute + 30_000L)), address)
    }

    @Test
    fun `the start of the programme is where the guide says it began`() = runBlocking {
        val gateway = FakeGateway(ArchiveCoverage.Known(deep()))

        val address = source(gateway).address(TimeShiftChoice.PROGRAMME_START)

        assertEquals(now - 40 * minute, gateway.addressAsked.single().atMs)
        assertEquals(40 * minute + 30_000L, (address as Outcome.Success).value.behindMs)
    }

    @Test
    fun `a place that is no longer offered is refused and no address is made`() = runBlocking {
        // The archive reaches back twenty minutes: half an hour is not in it.
        val shallow = ArchiveCover(spans = listOf(span(20.0, 5.0), span(5.0, -55.0, archived = false)), clockOffsetMs = 0L)
        val gateway = FakeGateway(ArchiveCoverage.Known(shallow))

        val address = source(gateway).address(TimeShiftChoice.MINUTES_30)

        assertEquals(Outcome.Failure(AppError.NOT_FOUND), address)
        assertTrue(gateway.addressAsked.isEmpty())
    }

    @Test
    fun `with no archive known there is no address`() = runBlocking {
        val gateway = FakeGateway(ArchiveCoverage.None)

        assertEquals(Outcome.Failure(AppError.NOT_FOUND), source(gateway).address(TimeShiftChoice.MINUTES_15))
        assertEquals(Outcome.Failure(AppError.NOT_FOUND), source(gateway, Resolver(null)).address(TimeShiftChoice.MINUTES_15))
        assertTrue(gateway.addressAsked.isEmpty())
    }

    @Test
    fun `the provider's own failure to make an address is passed on as it was`() = runBlocking {
        val gateway = FakeGateway(ArchiveCoverage.Known(deep()), url = Outcome.Failure(AppError.UNAUTHORIZED))

        assertEquals(Outcome.Failure(AppError.UNAUTHORIZED), source(gateway).address(TimeShiftChoice.MINUTES_15))
    }

    @Test
    fun `what is offered is read against the moment it is asked, not the moment it was drawn`() = runBlocking {
        val gateway = FakeGateway(ArchiveCoverage.Known(deep()))
        val rewind = source(gateway)
        rewind.choices()
        val first = gateway.coverageAsked.single()

        now += 10 * minute
        rewind.choices()

        assertEquals(first + 10 * minute, gateway.coverageAsked.last())
    }
}
