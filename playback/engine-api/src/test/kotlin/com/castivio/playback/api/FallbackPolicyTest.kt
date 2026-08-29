package com.castivio.playback.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback decision, which is the one piece of the player worth testing hardest.
 *
 * Every claim here is a sentence the user reads or a second they wait, and none of it needs
 * a decoder to check — which is the entire reason it lives in a pure module rather than in
 * the view model. A test that had to start ExoPlayer to ask "does a DRM failure offer the
 * backup engine?" is a test that would be written once and run never.
 */
class FallbackPolicyTest {

    /* ------------------------------------------------------- what the backup can fix */

    /**
     * The taxonomy, exhaustively, so a new reason cannot slip in unclassified.
     *
     * Written as a table over `entries` rather than as a list of cases, because the failure
     * mode is somebody adding a twelfth reason and the `when` defaulting it somewhere
     * plausible. Every value must appear below or this fails.
     */
    @Test
    fun `every reason states whether the backup could help`() {
        val helps = setOf(
            PlaybackError.DECODER_INIT,
            PlaybackError.DECODING,
            PlaybackError.CONTAINER,
            PlaybackError.UNSUPPORTED_FORMAT,
            PlaybackError.TIMEOUT,
            PlaybackError.UNKNOWN,
        )
        val cannot = setOf(
            PlaybackError.DRM,
            PlaybackError.NETWORK,
            PlaybackError.NOT_FOUND,
            PlaybackError.PERMISSION,
            PlaybackError.SOURCE,
        )

        assertEquals(
            "a reason was added and this table was not updated",
            PlaybackError.entries.toSet(),
            helps + cannot,
        )
        helps.forEach { assertTrue("$it should offer the backup", FallbackPolicy.canBackupHelp(it)) }
        cannot.forEach { assertFalse("$it must not offer the backup", FallbackPolicy.canBackupHelp(it)) }
    }

    /**
     * A decoder that refused and a format nothing supports now take the same route, and
     * this test records *why* — because the reason changed, not because the distinction
     * stopped mattering.
     *
     * Until LibVLC became the backup, the backup was a second Media3 profile with
     * `enableDecoderFallback` on: it could only walk the device's own MediaCodec list, so
     * `DECODER_INIT` (a decoder was listed and refused) was worth retrying and
     * `UNSUPPORTED_FORMAT` (there was no list) provably was not. `:playback:engine-vlc`
     * ships its own decoders, which is the single fact that moves the second case.
     *
     * The taxonomy itself is unchanged and still load-bearing — the two reasons print
     * different cards and mean different things in a report. Only the routing merged.
     */
    @Test
    fun `both decoder verdicts reach the backup now that it decodes in software`() {
        assertTrue(FallbackPolicy.canBackupHelp(PlaybackError.DECODER_INIT))
        assertTrue(
            "the backup bundles its own codecs, so a format the device lacks is exactly its job",
            FallbackPolicy.canBackupHelp(PlaybackError.UNSUPPORTED_FORMAT),
        )
        assertTrue(
            "the two reasons must stay distinct even though they route alike",
            PlaybackError.DECODER_INIT != PlaybackError.UNSUPPORTED_FORMAT,
        )
    }

    /**
     * Nothing about getting the bytes is a decoder question.
     *
     * These four were previously collapsed into two, and a `ContentDataSource` failure on a
     * local file arrived at the user as advice to check their network connection.
     */
    @Test
    fun `a source, a permission, a network and a missing file all decline the backup`() {
        for (error in listOf(
            PlaybackError.SOURCE,
            PlaybackError.PERMISSION,
            PlaybackError.NETWORK,
            PlaybackError.NOT_FOUND,
        )) {
            assertFalse("$error is not a decoder problem", FallbackPolicy.canBackupHelp(error))
        }
    }

    /* ----------------------------------------------------------- which engine is next */

    @Test
    fun `the primary hands a decoder refusal to the backup`() {
        assertEquals(
            EngineId.BACKUP,
            FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.DECODER_INIT),
        )
        assertEquals(
            EngineId.BACKUP,
            FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.DECODING),
        )
        assertEquals(
            EngineId.BACKUP,
            FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.UNSUPPORTED_FORMAT),
        )
    }

    @Test
    fun `the primary keeps a network or drm failure to itself`() {
        assertNull(FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.NETWORK))
        assertNull(FallbackPolicy.nextEngine(EngineId.PRIMARY, PlaybackError.DRM))
    }

    /**
     * There is no third engine, for any reason at all.
     *
     * The budget is spent once. A loop that found a successor here is how a dead source
     * becomes an unbounded spinner, so the assertion is over every reason rather than the
     * few that seem relevant.
     */
    @Test
    fun `the backup never falls back`() {
        for (error in PlaybackError.entries) {
            assertNull("$error found a third engine", FallbackPolicy.nextEngine(EngineId.BACKUP, error))
        }
    }

    /** Ordering and eligibility are one decision and must not drift apart. */
    @Test
    fun `nextEngine agrees with canBackupHelp on every reason`() {
        for (error in PlaybackError.entries) {
            val next = FallbackPolicy.nextEngine(EngineId.PRIMARY, error)
            assertEquals(
                "$error: nextEngine and canBackupHelp disagree",
                FallbackPolicy.canBackupHelp(error),
                next != null,
            )
        }
    }

    /* ------------------------------------------- who decides, and the button that was dead */

    /**
     * The regression this split was written for.
     *
     * One predicate used to answer both "could the backup help" and "should we switch
     * without asking", which meant every reason that made the button meaningful had
     * already consumed the fallback before the card was drawn. The button was unreachable
     * in every case.
     *
     * The property that fixes it: there must exist at least one reason where the backup
     * could help **and** the machine does not spend it. That is what makes the button
     * reachable at all, and asserting the existence rather than the specific value keeps
     * the test honest if the policy is retuned.
     */
    @Test
    fun `at least one reason leaves the fallback for the user to spend`() {
        val userDecides = PlaybackError.entries.filter {
            FallbackPolicy.canBackupHelp(it) && !FallbackPolicy.decideAutomatically(it)
        }
        assertTrue(
            "no reason leaves the choice to the user, so the backup button can never appear",
            userDecides.isNotEmpty(),
        )
        assertTrue(
            "an unidentified failure is the one the machine must not guess at",
            PlaybackError.UNKNOWN in userDecides,
        )
    }

    /** And the converse: nothing is switched automatically that the backup cannot address. */
    @Test
    fun `nothing is switched automatically unless the backup could help`() {
        for (error in PlaybackError.entries) {
            if (FallbackPolicy.decideAutomatically(error)) {
                assertTrue(
                    "$error is switched automatically but the backup cannot help it",
                    FallbackPolicy.canBackupHelp(error),
                )
            }
        }
    }

    /**
     * A timeout is its own reason and is not a codec verdict.
     *
     * It used to arrive as a decoder failure and then be relabelled "format not supported",
     * so a slow source was described to the user as an unplayable one — with no evidence
     * that anything about the format was wrong.
     */
    @Test
    fun `a timeout is distinct from every decoder reason`() {
        assertTrue(PlaybackError.TIMEOUT != PlaybackError.DECODER_INIT)
        assertTrue(PlaybackError.TIMEOUT != PlaybackError.UNSUPPORTED_FORMAT)
        assertTrue(
            "the budget exists to spend the other engine on silence",
            FallbackPolicy.canBackupHelp(PlaybackError.TIMEOUT),
        )
    }

    /**
     * Nothing renames a failure on its way to the user.
     *
     * `exhausted()` used to map DECODER and UNKNOWN to UNSUPPORTED_FORMAT once both engines
     * had refused, which is how an ordinary MP4 came to be reported as an unsupported
     * codec. It is gone, and this test is the marker that it must not come back: the reason
     * the engine reported is the reason the user is told.
     */
    @Test
    fun `the policy exposes no mapping that renames a failure`() {
        val renamers = FallbackPolicy::class.java.methods
            .filter { it.returnType == PlaybackError::class.java }
            .map { it.name }
        assertTrue(
            "FallbackPolicy has a method returning a PlaybackError ($renamers). That is how " +
                "'exhausted' relabelled every unclassified failure as an unsupported codec.",
            renamers.isEmpty(),
        )
    }

    /* ------------------------------------------------------------------- the memory */

    @Test
    fun `an unknown source opens on the primary`() {
        assertEquals(EngineId.PRIMARY, FallbackPolicy.first("http://x/a.ts", EngineMemory.NONE))
    }

    @Test
    fun `a source that needed the backup opens on the backup`() {
        val memory = RecordingMemory()
        memory.remember("http://x/a.ts", EngineId.BACKUP)

        assertEquals(
            "the point of the memory is that a source pays the fallback once",
            EngineId.BACKUP,
            FallbackPolicy.first("http://x/a.ts", memory),
        )
    }

    /**
     * The key drops the query, and that is not a detail.
     *
     * An Xtream stream URL carries credentials and a session token in its query, and both
     * rotate. Keyed on the whole URL the memory would miss on every launch — the failure
     * mode of a cache nobody notices, because it simply never helps.
     */
    @Test
    fun `the key survives a rotating token`() {
        val monday = "http://provider.tv:8080/live/1234.ts?token=abc&t=1700000000"
        val tuesday = "http://provider.tv:8080/live/1234.ts?token=zzz&t=1800000000"

        assertEquals(FallbackPolicy.sourceKey(monday), FallbackPolicy.sourceKey(tuesday))
        assertEquals("http://provider.tv:8080/live/1234.ts", FallbackPolicy.sourceKey(monday))
    }

    @Test
    fun `two different streams from one provider are two keys`() {
        val one = FallbackPolicy.sourceKey("http://provider.tv/live/1.ts?token=a")
        val two = FallbackPolicy.sourceKey("http://provider.tv/live/2.ts?token=a")
        assertTrue("a memory that collided would open the wrong engine", one != two)
    }

    /* --------------------------------------------------------------- the budget */

    /**
     * The deadline is a budget the user is spending, so it is bounded on both sides.
     *
     * Not asserted to an exact figure — that is a tuning decision and may move — but to the
     * range outside which it stops being the thing it is for. Under a second and a cold
     * cellular connection gets pulled off an engine that was about to succeed; over five
     * and every genuinely broken source costs the whole deadline before anything else is
     * tried.
     */
    @Test
    fun `the open deadline stays inside the range that makes it a budget`() {
        assertTrue(
            "a deadline under a second would abandon healthy slow starts",
            FallbackPolicy.OPEN_DEADLINE_MS >= 1_000,
        )
        assertTrue(
            "a deadline over five seconds is not a budget, it is a wait",
            FallbackPolicy.OPEN_DEADLINE_MS <= 5_000,
        )
    }

    @Test
    fun `the empty memory remembers nothing`() {
        EngineMemory.NONE.remember("anything", EngineId.BACKUP)
        assertNull(EngineMemory.NONE.preferred("anything"))
    }

    private class RecordingMemory : EngineMemory {
        private val entries = mutableMapOf<String, EngineId>()
        override fun preferred(sourceKey: String): EngineId? = entries[sourceKey]
        override fun remember(sourceKey: String, engine: EngineId) {
            entries[sourceKey] = engine
        }
    }
}
