package com.castivio.feature.home

import com.castivio.domain.FetchDiagnosis
import com.castivio.domain.FetchRoute
import com.castivio.domain.FetchStage
import com.castivio.domain.SectionLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines a debuggable build's error screen prints for a failed fetch.
 *
 * They are read off a television and photographed, so the shape is part of what is
 * tested: the same six rows in the same order, and figures that look the same in every
 * language.
 */
class FetchReadoutTest {

    @Test
    fun `a refusal by status reads as the answer it was, with nothing received`() {
        val readout = fetchReadout(FetchDiagnosis(FetchStage.ANSWER, httpStatus = 458, route = FetchRoute.M3U))

        assertEquals(
            listOf(
                "Stage: ANSWER (the provider answered with an error)",
                "HTTP: 458",
                "Exception: -",
                "Received: -",
                "Channels: 0 read · 0 saved",
                "Route: M3U",
            ),
            readout.lines(),
        )
    }

    @Test
    fun `a read that stopped part way says how far it got`() {
        val readout = fetchReadout(
            FetchDiagnosis(
                stage = FetchStage.READ,
                httpStatus = 200,
                exception = "SocketException<-ErrnoException",
                bytesReceived = 1_048_576,
                itemsRead = 4_317,
                itemsSaved = 4_000,
                route = FetchRoute.M3U,
            ),
        )

        assertEquals(
            listOf(
                "Stage: READ (reading the playlist)",
                "HTTP: 200",
                "Exception: SocketException<-ErrnoException",
                "Received: 1,048,576 bytes",
                "Channels: 4,317 read · 4,000 saved",
                "Route: M3U",
            ),
            readout.lines(),
        )
    }

    /** A panel's API failure reads with the same rows, and names the route it was. */
    @Test
    fun `a failure of the panel's API says it was the panel's`() {
        val readout = fetchReadout(
            FetchDiagnosis(
                stage = FetchStage.CONNECT,
                exception = "ConnectException",
                route = FetchRoute.XTREAM,
            ),
        )

        assertEquals(
            listOf(
                "Stage: CONNECT (no answer from the provider)",
                "HTTP: -",
                "Exception: ConnectException",
                "Received: -",
                "Channels: 0 read · 0 saved",
                "Route: XTREAM",
            ),
            readout.lines(),
        )
    }

    /**
     * The playlist tried after the panel said no. The rows are the playlist's; the last
     * says what the panel said first, so one photograph holds both answers.
     */
    @Test
    fun `a playlist read after its panel failed says what the panel said first`() {
        val panel = FetchDiagnosis(
            stage = FetchStage.ANSWER,
            httpStatus = 403,
            exception = "HttpStatusException",
            route = FetchRoute.XTREAM,
        )
        val readout = fetchReadout(
            FetchDiagnosis(stage = FetchStage.ANSWER, httpStatus = 458, route = FetchRoute.M3U, after = panel),
        )

        assertEquals("HTTP: 458", readout.lines()[1])
        assertEquals("Route: M3U after XTREAM ANSWER · HTTP 403 · HttpStatusException", readout.lines().last())
    }

    /** No body opened and an empty one are different findings, and read differently. */
    @Test
    fun `a body that was opened and sent nothing is zero bytes, not a dash`() {
        val readout = fetchReadout(FetchDiagnosis(FetchStage.READ, httpStatus = 200, bytesReceived = 0))

        assertTrue(readout, readout.lines().contains("Received: 0 bytes"))
    }

    /** An exception from outside the download says nothing about which way it was read. */
    @Test
    fun `an unknown route reads as a dash`() {
        assertEquals("Route: -", fetchReadout(FetchDiagnosis(FetchStage.OTHER)).lines().last())
    }

    @Test
    fun `every stage reads with its meaning beside its name`() {
        for (stage in FetchStage.entries) {
            val first = fetchReadout(FetchDiagnosis(stage)).lines().first()

            assertTrue(first, first.startsWith("Stage: ${stage.name} ("))
            assertTrue(first, first.endsWith(")"))
        }
    }

    /**
     * The order a photograph is compared in. A row that moved, or one dropped because its
     * figure was missing, would make two failures look different when they are not.
     */
    @Test
    fun `the six rows are always there, in the same order`() {
        val readouts = FetchStage.entries.flatMap { stage ->
            listOf(null, FetchRoute.M3U, FetchRoute.XTREAM).map { route -> fetchReadout(FetchDiagnosis(stage, route = route)) }
        }

        for (readout in readouts) {
            assertEquals(
                listOf("Stage", "HTTP", "Exception", "Received", "Channels", "Route"),
                readout.lines().map { it.substringBefore(':') },
            )
        }
    }

    /** The notice over a board that kept its rows has one line to say it in: every figure, in the same order. */
    @Test
    fun `the summary is the same figures on one line`() {
        val summary = fetchSummary(
            FetchDiagnosis(
                stage = FetchStage.READ,
                exception = "EOFException",
                bytesReceived = 48_201_334,
                itemsRead = 90_000,
                itemsSaved = 0,
                route = FetchRoute.XTREAM,
            ),
        )

        assertEquals("READ · HTTP - · EOFException · 48,201,334 bytes · 90,000 read / 0 saved · XTREAM", summary)
    }

    @Test
    fun `a summary has no figure that does not exist, only a dash`() {
        val summary = fetchSummary(FetchDiagnosis(FetchStage.CONNECT))

        assertEquals("CONNECT · HTTP - · - · - · 0 read / 0 saved · -", summary)
    }

    @Test
    fun `a refresh that is running says what has arrived`() {
        val figures = refreshFigures(SectionLoad.Loading(items = 41_203, groups = 57, groupsDone = 12, bytes = 34_707_456))

        assertEquals("41,203 rows · 12/57 categories · 33.1 MB", figures)
    }

    @Test
    fun `a refresh in one reply has no categories to count yet, and nothing arrived is a dash`() {
        val figures = refreshFigures(SectionLoad.Loading(items = 0, groups = 57, groupsDone = null, bytes = 0))

        assertEquals("0 rows · ?/57 categories · -", figures)
    }
}
