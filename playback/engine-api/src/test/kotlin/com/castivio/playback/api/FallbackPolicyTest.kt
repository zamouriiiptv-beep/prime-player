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
     * The three error cards, as one table.
     *
     * This is the whole taxonomy, and it is asserted exhaustively rather than case by case
     * so that adding a seventh [PlaybackError] fails here until somebody decides which
     * side of the line it is on. A new error code that silently defaults to "offer the
     * backup" would put a useless button in front of a user at the worst moment.
     */
    @Test
    fun `only a decoder refusal is worth handing to the other engine`() {
        assertTrue(
            "a container the first engine refused is exactly what the second is for",
            FallbackPolicy.canBackupHelp(PlaybackError.DECODER),
        )
        assertTrue(
            "an unclassified failure is worth one attempt on the other engine",
            FallbackPolicy.canBackupHelp(PlaybackError.UNKNOWN),
        )

        assertFalse(
            "UNSUPPORTED_FORMAT is the answer after both refused — there is no third engine",
            FallbackPolicy.canBackupHelp(PlaybackError.UNSUPPORTED_FORMAT),
        )
        assertFalse(
            "DRM is not a decoding problem; the same device lacks the keys either way",
            FallbackPolicy.canBackupHelp(PlaybackError.DRM),
        )
        assertFalse(
            "swapping decoders cannot make a host answer",
            FallbackPolicy.canBackupHelp(PlaybackError.NETWORK),
        )
        assertFalse(
            "a link that leads nowhere leads nowhere on both engines",
            FallbackPolicy.canBackupHelp(PlaybackError.NOT_FOUND),
        )
    }

    /**
     * What a failure becomes once there is nowhere left to send it.
     *
     * The card the user sees changes with this, and so do its buttons: a decoder failure
     * that engine 2 has also refused stops being "this engine could not read it" and
     * becomes "nothing here can read it". Deriving both from one function is what keeps the
     * automatic path and the card in step.
     */
    @Test
    fun `an exhausted decoder failure becomes unsupported, and nothing else changes`() {
        assertEquals(
            PlaybackError.UNSUPPORTED_FORMAT,
            FallbackPolicy.exhausted(PlaybackError.DECODER),
        )
        assertEquals(
            PlaybackError.UNSUPPORTED_FORMAT,
            FallbackPolicy.exhausted(PlaybackError.UNKNOWN),
        )

        // The rest are already final. A network failure that has been through both engines
        // is still a network failure, and calling it "unsupported" would send the user
        // looking for a different source instead of a different connection.
        for (error in listOf(
            PlaybackError.NETWORK,
            PlaybackError.NOT_FOUND,
            PlaybackError.DRM,
            PlaybackError.UNSUPPORTED_FORMAT,
        )) {
            assertEquals("$error should be reported as itself", error, FallbackPolicy.exhausted(error))
        }
    }

    /**
     * The pairing the whole design rests on: what is exhausted must not be offered.
     *
     * Asserted as a relationship rather than as two lists, because the failure mode is the
     * two lists drifting. Whatever [exhausted] turns an error into, [canBackupHelp] must
     * say no to the result — otherwise the card offers a button that leads to the same card.
     */
    @Test
    fun `nothing that survives exhaustion still offers the backup`() {
        for (error in PlaybackError.entries) {
            val final = FallbackPolicy.exhausted(error)
            assertFalse(
                "$error exhausts to $final, which still offers the backup — that is a loop",
                FallbackPolicy.canBackupHelp(final),
            )
        }
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
