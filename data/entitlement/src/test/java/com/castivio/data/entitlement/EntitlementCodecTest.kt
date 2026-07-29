package com.castivio.data.entitlement

import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.identity.MacAddress
import com.castivio.domain.time.ClockState
import com.castivio.domain.time.TimeAnchor
import com.castivio.domain.time.TimeAnchorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bytes in, the same facts out — and nothing invented when they are not.
 *
 * The failure this file exists to prevent is the quiet one: a blob that half-decodes
 * into a record with a plausible-looking expiry nobody ever wrote. Every required field
 * missing means null, and null means the app has no entitlement, which is a state it
 * already handles honestly.
 */
class EntitlementCodecTest {

    private val t0 = 1_772_323_200_000L
    private val day = 24L * 60 * 60 * 1000

    private val full = EntitlementRecord(
        macAddress = MacAddress.parse("2F:19:EB:20:44:7C")!!,
        identityVersion = 1,
        plan = Plan.ANNUAL,
        trialStartedAtMs = t0 - 30 * day,
        trialExpiresAtMs = t0 - 23 * day,
        subscriptionExpiresAtMs = t0 + 365 * day,
        establishedAtMs = t0 - 30 * day,
        lastVerifiedAtMs = t0 - 1,
        revokedAtMs = null,
        maxObservedTimeMs = t0,
        serverSignature = "MEUCIQDxKq+dGm7lE1RfW==",
    )

    // ------------------------------------------------------------- the entitlement

    @Test
    fun `a full record survives the round trip`() {
        assertEquals(full, EntitlementCodec.decodeRecord(EntitlementCodec.encode(full)))
    }

    @Test
    fun `a minimal record survives the round trip`() {
        val minimal = EntitlementRecord(
            macAddress = MacAddress.parse("2F:19:EB:20:44:7C")!!,
            identityVersion = 1,
            plan = Plan.LIFETIME,
            establishedAtMs = t0,
            maxObservedTimeMs = t0,
        )

        assertEquals(minimal, EntitlementCodec.decodeRecord(EntitlementCodec.encode(minimal)))
    }

    @Test
    fun `a revocation survives the round trip`() {
        val revoked = full.copy(revokedAtMs = t0 - day)

        assertEquals(revoked, EntitlementCodec.decodeRecord(EntitlementCodec.encode(revoked)))
    }

    @Test
    fun `every plan survives the round trip`() {
        for (plan in Plan.entries) {
            val record = full.copy(plan = plan)

            assertEquals("$plan", record, EntitlementCodec.decodeRecord(EntitlementCodec.encode(record)))
        }
    }

    /** A null field is written as no field, so absent and null read back the same. */
    @Test
    fun `a null field does not become a zero`() {
        val decoded = EntitlementCodec.decodeRecord(
            EntitlementCodec.encode(full.copy(lastVerifiedAtMs = null, serverSignature = null)),
        )

        assertNull(decoded?.lastVerifiedAtMs)
        assertNull(decoded?.serverSignature)
    }

    /** Base64 signatures contain `=`, so the split has to be on the first one only. */
    @Test
    fun `a value containing an equals sign is not truncated`() {
        val signed = full.copy(serverSignature = "a=b=c==")

        assertEquals("a=b=c==", EntitlementCodec.decodeRecord(EntitlementCodec.encode(signed))?.serverSignature)
    }

    // ----------------------------------------------------------------- refusing

    @Test
    fun `a record missing something required decodes to nothing`() {
        val required = listOf("mac", "identityVersion", "plan", "establishedAt", "maxObservedTime")

        for (field in required) {
            val without = EntitlementCodec.encode(full)
                .decodeToString()
                .lineSequence()
                .filterNot { it.startsWith("$field=") }
                .joinToString("\n")

            assertNull(field, EntitlementCodec.decodeRecord(without.encodeToByteArray()))
        }
    }

    @Test
    fun `nonsense decodes to nothing rather than to a guess`() {
        val rubbish = listOf(
            "",
            "not a record at all",
            "format=v1\n",
            "mac=2F:19:EB:20:44:7C\nplan=TRIAL\n",
            // A plan this build has never heard of is not a plan to improvise around.
            EntitlementCodec.encode(full).decodeToString().replace("plan=ANNUAL", "plan=PLATINUM"),
            // Nor is an address that is not one.
            EntitlementCodec.encode(full).decodeToString().replace("mac=2F:19:EB:20:44:7C", "mac=hello"),
        )

        for (text in rubbish) {
            assertNull(text.take(30), EntitlementCodec.decodeRecord(text.encodeToByteArray()))
        }
    }

    /** A blob from a format this build does not know is not a blob to interpret. */
    @Test
    fun `an unknown format decodes to nothing`() {
        val future = EntitlementCodec.encode(full).decodeToString().replace("format=v1", "format=v9")

        assertNull(EntitlementCodec.decodeRecord(future.encodeToByteArray()))
    }

    /**
     * The other direction: a build that adds a field must still be readable by one that
     * does not know it, or a downgrade strands a licence.
     */
    @Test
    fun `an unknown field is ignored rather than fatal`() {
        val extended = EntitlementCodec.encode(full).decodeToString() + "somethingNew=42\n"

        assertEquals(full, EntitlementCodec.decodeRecord(extended.encodeToByteArray()))
    }

    // -------------------------------------------------------------------- the clock

    @Test
    fun `a clock with an anchor survives the round trip`() {
        val state = ClockState(
            highWaterMarkMs = t0,
            anchor = TimeAnchor(t0, 90_000L, "boot-a", TimeAnchorSource.LICENCE_SERVER),
        )

        assertEquals(state, EntitlementCodec.decodeClock(EntitlementCodec.encode(state)))
    }

    /** No anchor is the ordinary state of a device that has never reached our host. */
    @Test
    fun `a clock with no anchor survives the round trip`() {
        val state = ClockState(highWaterMarkMs = t0)

        assertEquals(state, EntitlementCodec.decodeClock(EntitlementCodec.encode(state)))
    }

    @Test
    fun `a fresh clock survives the round trip`() {
        assertEquals(ClockState(), EntitlementCodec.decodeClock(EntitlementCodec.encode(ClockState())))
    }

    /**
     * A half-written anchor would project nonsense forward. Dropping it costs the
     * precision of one licence check; keeping it could cost a subscription.
     */
    @Test
    fun `a partial anchor is dropped, not repaired`() {
        val complete = EntitlementCodec.encode(
            ClockState(t0, TimeAnchor(t0, 90_000L, "boot-a", TimeAnchorSource.LICENCE_SERVER)),
        ).decodeToString()

        for (field in listOf("anchorEpoch", "anchorElapsed", "anchorBoot", "anchorSource")) {
            val partial = complete.lineSequence().filterNot { it.startsWith("$field=") }.joinToString("\n")

            assertEquals(field, ClockState(highWaterMarkMs = t0), EntitlementCodec.decodeClock(partial.encodeToByteArray()))
        }
    }

    @Test
    fun `a clock without a mark decodes to nothing`() {
        assertNull(EntitlementCodec.decodeClock("format=v1\nanchorEpoch=1\n".encodeToByteArray()))
    }
}
