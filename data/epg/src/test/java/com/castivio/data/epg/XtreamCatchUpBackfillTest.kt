package com.castivio.data.epg

import com.castivio.core.common.AppDispatchers
import com.castivio.data.parsing.WholeSectionUnsupported
import com.castivio.data.parsing.XtreamArchiveBackfill
import com.castivio.domain.BackfillOutcome
import com.castivio.domain.CatchUpBackfillMarks
import com.castivio.domain.CatchUpHours
import com.castivio.domain.CatchUpHoursWriter
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.Reader
import java.io.StringReader

/**
 * The backfill of a catalogue's archive hours: once, one request, one write, and no catalogue read again.
 *
 * No server and no database: the panel, the writer and the mark are fakes that count what they are asked, and what these
 * tests hold is mostly that the counts are zero or one.
 */
class XtreamCatchUpBackfillTest {

    private val list = """[
        {"name":"Nova","stream_id":11,"tv_archive":1,"tv_archive_duration":7},
        {"name":"Atlas","stream_id":12,"tv_archive":0,"tv_archive_duration":7},
        {"name":"Orion","stream_id":13,"tv_archive":1,"tv_archive_duration":3}
    ]"""

    private val imported = SyncState(lastImportAtMs = 1_000L, itemCount = 500)

    @Test
    fun `the rows that keep an archive are written once, by their stable ids, in one write`() = runBlocking {
        val fixture = Fixture(sync = imported, answers = listOf { StringReader(list) })

        fixture.backfill().ensure()

        assertEquals(1, fixture.writer.calls.size)
        val (sourceId, updates) = fixture.writer.calls.single()
        assertEquals("src", sourceId)
        assertEquals(XtreamArchiveBackfill.updates("src", StringReader(list)), updates)
        assertEquals(listOf(7 * 24, 3 * 24), updates.map { it.hours })
        assertEquals(BackfillOutcome.DONE, fixture.marks.outcomeOf("src"))
    }

    @Test
    fun `once done it is not asked again`() = runBlocking {
        val fixture = Fixture(sync = imported, answers = listOf { StringReader(list) })

        fixture.backfill().ensure()
        fixture.backfill().ensure()

        assertEquals("one request", 1, fixture.requests)
        assertEquals("one write", 1, fixture.writer.calls.size)
    }

    @Test
    fun `a subscription with no catalogue yet is marked done and nothing is asked`() = runBlocking {
        val fixture = Fixture(sync = SyncState(), answers = emptyList())

        fixture.backfill().ensure()

        assertEquals(0, fixture.requests)
        assertTrue(fixture.writer.calls.isEmpty())
        assertEquals(BackfillOutcome.DONE, fixture.marks.outcomeOf("src"))
    }

    @Test
    fun `a panel that refuses the whole list is marked refused and nothing is read again`() = runBlocking {
        val fixture = Fixture(sync = imported, answers = listOf { throw WholeSectionUnsupported("HTTP 405", null, 405) })

        fixture.backfill().ensure()
        fixture.backfill().ensure()

        assertEquals("one request, never repeated", 1, fixture.requests)
        assertTrue(fixture.writer.calls.isEmpty())
        assertEquals(BackfillOutcome.REFUSED, fixture.marks.outcomeOf("src"))
    }

    @Test
    fun `an answer that is not a list is a refusal too`() = runBlocking {
        val fixture = Fixture(sync = imported, answers = listOf { StringReader("<html>not json</html>") })

        fixture.backfill().ensure()

        assertEquals(BackfillOutcome.REFUSED, fixture.marks.outcomeOf("src"))
        assertTrue(fixture.writer.calls.isEmpty())
    }

    @Test
    fun `a failed connection writes no mark, so the next start asks once more`() = runBlocking {
        val fixture = Fixture(
            sync = imported,
            answers = listOf({ throw IOException("offline") }, { StringReader(list) }),
        )

        fixture.backfill().ensure()
        assertNull(fixture.marks.outcomeOf("src"))
        assertTrue(fixture.writer.calls.isEmpty())

        fixture.backfill().ensure()
        assertEquals(BackfillOutcome.DONE, fixture.marks.outcomeOf("src"))
        assertEquals(2, fixture.requests)
    }

    @Test
    fun `a subscription that is not an Xtream panel is left alone`() = runBlocking {
        val fixture = Fixture(sync = imported, kind = SourceKind.PORTAL, answers = emptyList())

        fixture.backfill().ensure()

        assertEquals(0, fixture.requests)
        assertNull(fixture.marks.outcomeOf("src"))
    }

    // ---------------------------------------------------------------------------------------- fakes

    private class Fixture(
        sync: SyncState,
        kind: SourceKind = SourceKind.XTREAM,
        private val answers: List<() -> Reader?>,
    ) {
        var requests = 0
        val writer = FakeWriter()
        val marks = FakeMarks()
        private val source = ProviderSource(
            id = "src",
            kind = kind,
            label = "test",
            url = "http://panel:8080",
            username = "u",
            password = "p",
            sync = sync,
        )

        fun backfill() = XtreamCatchUpBackfill(
            client = OkHttpClient(),
            sources = FakeSources(source),
            writer = { writer },
            marks = marks,
            dispatchers = TestDispatchers,
            apiFactory = { _, _ ->
                LiveList {
                    val answer = answers[requests.coerceAtMost(answers.lastIndex)]
                    requests++
                    answer()
                }
            },
        )
    }

    private class FakeWriter : CatchUpHoursWriter {
        val calls = mutableListOf<Pair<String, List<CatchUpHours>>>()

        override fun apply(sourceId: String, updates: List<CatchUpHours>): Int {
            calls += sourceId to updates
            return updates.size
        }
    }

    private class FakeMarks : CatchUpBackfillMarks {
        private val stored = mutableMapOf<String, BackfillOutcome>()
        override fun outcomeOf(sourceId: String): BackfillOutcome? = stored[sourceId]
        override fun record(sourceId: String, outcome: BackfillOutcome) {
            stored[sourceId] = outcome
        }
    }

    private object TestDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class FakeSources(private val stored: ProviderSource) : SourceRepository {
        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource = stored
        override fun sources(): Flow<List<ProviderSource>> = flowOf(listOf(stored))
        override fun active(): Flow<ProviderSource?> = flowOf(stored)
        override suspend fun activeNow(): ProviderSource = stored
        override suspend fun get(id: String): ProviderSource = stored
        override suspend fun save(source: ProviderSource) = Unit
        override suspend fun setActive(id: String) = Unit
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) = Unit
    }
}
