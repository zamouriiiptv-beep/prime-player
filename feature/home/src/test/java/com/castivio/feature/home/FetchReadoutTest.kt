package com.castivio.feature.home

import com.castivio.domain.FetchDiagnosis
import com.castivio.domain.FetchStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines a debuggable build's error screen prints for a failed fetch.
 *
 * They are read off a television and photographed, so the shape is part of what is
 * tested: the same five rows in the same order, and figures that look the same in every
 * language.
 */
class FetchReadoutTest {

    @Test
    fun `a refusal by status reads as the answer it was, with nothing received`() {
        val readout = fetchReadout(FetchDiagnosis(FetchStage.ANSWER, httpStatus = 458))

        assertEquals(
            listOf(
                "Stage: ANSWER (the provider answered with an error)",
                "HTTP: 458",
                "Exception: -",
                "Received: -",
                "Channels: 0 read · 0 saved",
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
            ),
        )

        assertEquals(
            listOf(
                "Stage: READ (reading the playlist)",
                "HTTP: 200",
                "Exception: SocketException<-ErrnoException",
                "Received: 1,048,576 bytes",
                "Channels: 4,317 read · 4,000 saved",
            ),
            readout.lines(),
        )
    }

    /** No body opened and an empty one are different findings, and read differently. */
    @Test
    fun `a body that was opened and sent nothing is zero bytes, not a dash`() {
        val readout = fetchReadout(FetchDiagnosis(FetchStage.READ, httpStatus = 200, bytesReceived = 0))

        assertTrue(readout, readout.lines().contains("Received: 0 bytes"))
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
    fun `the five rows are always there, in the same order`() {
        val labels = FetchStage.entries.map { stage ->
            fetchReadout(FetchDiagnosis(stage)).lines().map { it.substringBefore(':') }
        }

        for (rows in labels) {
            assertEquals(listOf("Stage", "HTTP", "Exception", "Received", "Channels"), rows)
        }
    }
}
