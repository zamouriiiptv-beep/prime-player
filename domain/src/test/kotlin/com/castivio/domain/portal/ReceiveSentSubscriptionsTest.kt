package com.castivio.domain.portal

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.CatalogImporter
import com.castivio.domain.ImportProgress
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.ProviderValidator
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import com.castivio.domain.activation.ActivateProvider
import com.castivio.domain.time.TimeAnchorSource
import com.castivio.domain.time.TimeReading
import com.castivio.domain.time.TimeTrust
import com.castivio.domain.time.TrustedTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a press of Refresh does with what the website sent.
 *
 * The part that matters most is what does *not* happen: a subscription the device would
 * have refused when typed is not added because it arrived another way, and one that was
 * not added is not acknowledged, so it stays visible where its owner can fix it.
 */
class ReceiveSentSubscriptionsTest {

    private val t0 = 1_772_323_200_000L

    // --------------------------------------------------------------- the harness

    private class Inbox : SubscriptionInbox {
        var announceAnswer: Outcome<Unit> = Outcome.Success(Unit)
        var waitingAnswer: Outcome<List<SentSubscription>> = Outcome.Success(emptyList())
        var acknowledgeAnswer: Outcome<Unit> = Outcome.Success(Unit)

        var announces = 0
        var asked = 0
        val acknowledged = mutableListOf<SentSubscription>()
        var acknowledgements = 0

        override suspend fun announce(): Outcome<Unit> {
            announces++
            return announceAnswer
        }

        override suspend fun waiting(): Outcome<List<SentSubscription>> {
            asked++
            return waitingAnswer
        }

        override suspend fun acknowledge(taken: List<SentSubscription>): Outcome<Unit> {
            acknowledgements++
            acknowledged += taken
            return acknowledgeAnswer
        }

        fun sent(vararg items: SentSubscription) {
            waitingAnswer = Outcome.Success(items.toList())
        }
    }

    /** A subscription's identity is what it is, as in the data layer, so a repeat is the same row. */
    private class Sources : SourceRepository {
        val stored = linkedMapOf<String, ProviderSource>()
        val labels = mutableMapOf<String, String?>()
        val registered = mutableListOf<PlaylistSource>()
        var activeId: String? = null

        private fun idOf(source: PlaylistSource): String = when (source) {
            is PlaylistSource.Xtream -> "x:${source.host}:${source.username}"
            is PlaylistSource.M3u -> "m:${source.url}"
            is PlaylistSource.LocalFile -> "f:${source.uri}"
            is PlaylistSource.Portal -> "p:${source.url}"
        }

        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource {
            registered += source
            val id = idOf(source)
            labels[id] = label
            return stored.getOrPut(id) {
                ProviderSource(id = id, kind = SourceKind.XTREAM, label = label ?: "New", url = null)
            }
        }

        override fun sources(): Flow<List<ProviderSource>> = throw UnsupportedOperationException()
        override fun active(): Flow<ProviderSource?> = throw UnsupportedOperationException()
        override suspend fun activeNow(): ProviderSource? = activeId?.let(stored::get)
        override suspend fun get(id: String): ProviderSource? = stored[id]
        override suspend fun save(source: ProviderSource) { stored[source.id] = source }
        override suspend fun setActive(id: String) { activeId = id }
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) { stored.remove(id) }
    }

    /** Answers per account name, so one line can be wrong while another is fine. */
    private class Validator : ProviderValidator {
        val answers = mutableMapOf<String, Outcome<ProviderStatus>>()
        val asked = mutableListOf<PlaylistSource>()

        override suspend fun validate(source: PlaylistSource): Outcome<ProviderStatus> {
            asked += source
            val name = (source as? PlaylistSource.Xtream)?.username
            return answers[name] ?: Outcome.Success(ProviderStatus(usable = true))
        }
    }

    /** Nothing may be downloaded while a subscription is being added: sections load when they are opened. */
    private object NoDownload : CatalogImporter {
        override fun import(source: PlaylistSource): Flow<ImportProgress> =
            throw AssertionError("adding a sent subscription must not download a catalogue")

        override suspend fun isUpToDate(source: PlaylistSource): Boolean = false
    }

    private class FixedClock(private val nowMs: Long) : TrustedTime {
        override fun now() = TimeReading(nowMs, TimeTrust.NETWORK)
        override fun anchor(epochMs: Long, source: TimeAnchorSource) = now()
    }

    private val inbox = Inbox()
    private val sources = Sources()
    private val validator = Validator()
    private val receive = ReceiveSentSubscriptions(inbox, ActivateProvider(validator, NoDownload, sources), FixedClock(t0))

    private fun xtream(
        id: String,
        user: String = "bob",
        password: String = "hunter2",
        server: String = "line.example.com:8080",
        name: String = "",
        version: String = "v-$id",
    ) = SentSubscription(id, version, name, SentSource.Xtream(server, user, password))

    private fun playlist(id: String, url: String = "https://list.example.com/$id.m3u8", name: String = "") =
        SentSubscription(id, "v-$id", name, SentSource.Playlist(url))

    private fun refusal(error: AppError) = Outcome.Failure(error)

    // ------------------------------------------------------------------ the answers

    @Test
    fun `nothing was sent is an answer and not a failure`() = runTest {
        val reception = receive.receive()

        assertEquals(Reception.NothingSent, reception)
        assertEquals(0, inbox.acknowledgements)
        assertNull(sources.activeId)
    }

    @Test
    fun `an Xtream line is checked, added, made the one shown, and acknowledged by its version`() = runTest {
        val line = xtream("a", name = "Home")
        inbox.sent(line)

        val reception = receive.receive()

        assertEquals(Reception.Added(added = 1, rejected = 0), reception)
        assertEquals(1, validator.asked.size)
        assertEquals(PlaylistSource.Xtream("http://line.example.com:8080", "bob", "hunter2"), sources.registered.single())
        assertEquals("x:http://line.example.com:8080:bob", sources.activeId)
        assertEquals("Home", sources.labels["x:http://line.example.com:8080:bob"])
        assertEquals(listOf(line), inbox.acknowledged)
    }

    @Test
    fun `a server typed the way the television accepts it is normalised the way the television does`() = runTest {
        // No scheme, a path the panel handed out, and a space from the paste.
        inbox.sent(xtream("a", server = "  line.example.com:8080/c/  "))

        receive.receive()

        val added = sources.registered.single() as PlaylistSource.Xtream
        assertEquals("http://line.example.com:8080", added.host)
    }

    @Test
    fun `a playlist link is adopted without asking a panel`() = runTest {
        val link = playlist("p")
        inbox.sent(link)

        val reception = receive.receive()

        assertEquals(Reception.Added(1, 0), reception)
        assertTrue(validator.asked.isEmpty())
        assertEquals(PlaylistSource.M3u("https://list.example.com/p.m3u8"), sources.registered.single())
        assertEquals(listOf(link), inbox.acknowledged)
    }

    @Test
    fun `a blank name leaves the label to be derived, and a given one is kept`() = runTest {
        inbox.sent(playlist("a"), playlist("b", name = "Sports"))

        receive.receive()

        assertNull(sources.labels["m:https://list.example.com/a.m3u8"])
        assertEquals("Sports", sources.labels["m:https://list.example.com/b.m3u8"])
    }

    @Test
    fun `several are added in the order they came and the last is the one shown`() = runTest {
        inbox.sent(playlist("old"), playlist("middle"), playlist("new"))

        val reception = receive.receive()

        assertEquals(Reception.Added(3, 0), reception)
        assertEquals("m:https://list.example.com/new.m3u8", sources.activeId)
        assertEquals(listOf("old", "middle", "new"), inbox.acknowledged.map { it.id })
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    fun `a wrong password is not added and is not acknowledged, while the others still are`() = runTest {
        val good = playlist("good")
        val bad = xtream("bad", user = "mallory")
        validator.answers["mallory"] = refusal(AppError.UNAUTHORIZED)
        inbox.sent(bad, good)

        val reception = receive.receive()

        assertEquals(Reception.Added(added = 1, rejected = 1), reception)
        assertEquals(listOf(good), inbox.acknowledged)
        assertEquals("m:https://list.example.com/good.m3u8", sources.activeId)
        assertTrue("the refused line was never saved", sources.registered.none { it is PlaylistSource.Xtream })
    }

    @Test
    fun `a subscription the panel says has ended is refused as it would be when typed`() = runTest {
        validator.answers["bob"] = Outcome.Success(ProviderStatus(usable = false, expiresAtMs = t0 - 1, statusLabel = "Expired"))
        inbox.sent(xtream("a"))

        val reception = receive.receive()

        assertEquals(Reception.Rejected(1), reception)
        assertEquals(0, inbox.acknowledgements)
        assertNull(sources.activeId)
    }

    @Test
    fun `when none can be added all of it stays waiting and nothing becomes the one shown`() = runTest {
        validator.answers["bob"] = refusal(AppError.NETWORK_UNAVAILABLE)
        validator.answers["alice"] = refusal(AppError.UNAUTHORIZED)
        inbox.sent(xtream("a"), xtream("b", user = "alice"))

        val reception = receive.receive()

        assertEquals(Reception.Rejected(2), reception)
        assertEquals(0, inbox.acknowledgements)
        assertNull(sources.activeId)
    }

    @Test
    fun `what the television's own rules refuse is refused without asking anyone`() = runTest {
        // A link that is not one, and a server that is not an address, however they were typed.
        inbox.sent(playlist("a", url = "ftp://list.example.com/a.m3u"), xtream("b", server = "not a server"))

        val reception = receive.receive()

        assertEquals(Reception.Rejected(2), reception)
        assertTrue(validator.asked.isEmpty())
        assertTrue(sources.registered.isEmpty())
        assertEquals(0, inbox.acknowledgements)
    }

    // ------------------------------------------------------------------ the portal

    @Test
    fun `an unreachable portal is worth another press, and nothing is asked after it fails`() = runTest {
        inbox.announceAnswer = refusal(AppError.NETWORK_UNAVAILABLE)

        assertEquals(Reception.Unreachable, receive.receive())
        assertEquals(0, inbox.asked)

        inbox.announceAnswer = Outcome.Success(Unit)
        inbox.waitingAnswer = refusal(AppError.TIMEOUT)

        assertEquals(Reception.Unreachable, receive.receive())
        assertEquals(0, inbox.acknowledgements)
    }

    @Test
    fun `an answer the portal gives that cannot be read is also worth another press`() = runTest {
        inbox.waitingAnswer = refusal(AppError.SERVER_ERROR)

        assertEquals(Reception.Unreachable, receive.receive())
    }

    @Test
    fun `a portal that knows this address under another key is not worth retrying`() = runTest {
        inbox.announceAnswer = refusal(AppError.UNAUTHORIZED)

        assertEquals(Reception.NotRecognised, receive.receive())
        assertEquals("it must not go on to ask for what is waiting", 0, inbox.asked)

        inbox.announceAnswer = Outcome.Success(Unit)
        inbox.waitingAnswer = refusal(AppError.UNAUTHORIZED)

        assertEquals(Reception.NotRecognised, receive.receive())
    }

    @Test
    fun `the device announces itself on every press`() = runTest {
        receive.receive()
        receive.receive()

        assertEquals(2, inbox.announces)
    }

    @Test
    fun `announcing on its own only tells the portal, and a failure is not an error`() = runTest {
        inbox.announceAnswer = refusal(AppError.NETWORK_UNAVAILABLE)

        receive.announce()

        assertEquals(1, inbox.announces)
        assertEquals(0, inbox.asked)
    }

    @Test
    fun `an acknowledgement that does not get through changes nothing for the user`() = runTest {
        inbox.acknowledgeAnswer = refusal(AppError.NETWORK_UNAVAILABLE)
        inbox.sent(playlist("a"))

        val reception = receive.receive()

        assertEquals(Reception.Added(1, 0), reception)
        assertEquals("m:https://list.example.com/a.m3u8", sources.activeId)
    }

    @Test
    fun `a subscription taken again after a lost acknowledgement is the same one and not a second`() = runTest {
        inbox.acknowledgeAnswer = refusal(AppError.NETWORK_UNAVAILABLE)
        inbox.sent(xtream("a"))

        receive.receive()
        receive.receive()

        assertEquals(1, sources.stored.size)
    }
}
