package com.castivio.data.entitlement

import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.identity.MacAddress
import com.castivio.domain.time.ClockState
import com.castivio.domain.time.TimeAnchor
import com.castivio.domain.time.TimeAnchorSource

/**
 * The record and the clock, as bytes.
 *
 * A tagged `key=value` line format rather than JSON, for two reasons that both come down
 * to the same thing: there is no serialisation library in this project's dependencies
 * and adding one to store nine numbers would be absurd, and a format this small can be
 * read by eye during a support call once it has been decrypted.
 *
 * Forward compatible in the direction that matters. An unknown key is ignored and a
 * missing key takes its default, so a build that adds a field can still read what an
 * older build wrote, and an older build does not choke on what a newer one wrote. What
 * is *not* tolerated is a missing required field or an unreadable version — those come
 * back as null, and null means "nothing is stored", which routes to the licence screen
 * rather than to a guess.
 */
internal object EntitlementCodec {

    private const val VERSION = "v1"

    // ------------------------------------------------------------- the entitlement

    fun encode(record: EntitlementRecord): ByteArray = buildString {
        line("format", VERSION)
        line("mac", record.macAddress.value)
        line("identityVersion", record.identityVersion)
        line("plan", record.plan.name)
        line("trialStartedAt", record.trialStartedAtMs)
        line("trialExpiresAt", record.trialExpiresAtMs)
        line("subscriptionExpiresAt", record.subscriptionExpiresAtMs)
        line("establishedAt", record.establishedAtMs)
        line("lastVerifiedAt", record.lastVerifiedAtMs)
        line("revokedAt", record.revokedAtMs)
        line("maxObservedTime", record.maxObservedTimeMs)
        line("signature", record.serverSignature)
    }.encodeToByteArray()

    fun decodeRecord(bytes: ByteArray): EntitlementRecord? {
        val fields = fields(bytes) ?: return null

        // The five that have no sensible default. A record missing any of them is not a
        // partial record, it is a corrupt one, and inventing the rest would be inventing
        // an entitlement.
        val mac = fields["mac"]?.let(MacAddress::parse) ?: return null
        val identityVersion = fields["identityVersion"]?.toIntOrNull() ?: return null
        val plan = fields["plan"]?.let { name -> Plan.entries.firstOrNull { it.name == name } } ?: return null
        val establishedAt = fields["establishedAt"]?.toLongOrNull() ?: return null
        val maxObserved = fields["maxObservedTime"]?.toLongOrNull() ?: return null

        return EntitlementRecord(
            macAddress = mac,
            identityVersion = identityVersion,
            plan = plan,
            trialStartedAtMs = fields["trialStartedAt"]?.toLongOrNull(),
            trialExpiresAtMs = fields["trialExpiresAt"]?.toLongOrNull(),
            subscriptionExpiresAtMs = fields["subscriptionExpiresAt"]?.toLongOrNull(),
            establishedAtMs = establishedAt,
            lastVerifiedAtMs = fields["lastVerifiedAt"]?.toLongOrNull(),
            revokedAtMs = fields["revokedAt"]?.toLongOrNull(),
            maxObservedTimeMs = maxObserved,
            serverSignature = fields["signature"],
        )
    }

    // -------------------------------------------------------------------- the clock

    fun encode(state: ClockState): ByteArray = buildString {
        line("format", VERSION)
        line("highWaterMark", state.highWaterMarkMs)
        state.anchor?.let { anchor ->
            line("anchorEpoch", anchor.epochMs)
            line("anchorElapsed", anchor.elapsedRealtimeMs)
            line("anchorBoot", anchor.bootId)
            line("anchorSource", anchor.source.name)
        }
    }.encodeToByteArray()

    /**
     * Null only when the blob is unreadable. A clock state with no anchor is perfectly
     * ordinary — it is what every device has until it first reaches our licence host.
     */
    fun decodeClock(bytes: ByteArray): ClockState? {
        val fields = fields(bytes) ?: return null
        val mark = fields["highWaterMark"]?.toLongOrNull() ?: return null

        val anchor = anchor(fields)
        return ClockState(highWaterMarkMs = mark, anchor = anchor)
    }

    private fun anchor(fields: Map<String, String>): TimeAnchor? {
        // All four or none: a half-written anchor projects nonsense, and dropping it
        // costs only the precision of one licence check.
        val epoch = fields["anchorEpoch"]?.toLongOrNull() ?: return null
        val elapsed = fields["anchorElapsed"]?.toLongOrNull() ?: return null
        val boot = fields["anchorBoot"]?.takeIf { it.isNotEmpty() } ?: return null
        val source = fields["anchorSource"]
            ?.let { name -> TimeAnchorSource.entries.firstOrNull { it.name == name } }
            ?: return null

        return TimeAnchor(epoch, elapsed, boot, source)
    }

    // ------------------------------------------------------------------- the format

    private fun StringBuilder.line(key: String, value: Any?) {
        // A null field is written as no field at all, so absent and null are the same
        // thing on the way back in and there is only one case to handle.
        if (value == null) return
        append(key).append('=').append(value).append('\n')
    }

    private fun fields(bytes: ByteArray): Map<String, String>? {
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return null

        val fields = text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()

        // A blob written by a format this build does not know is not a blob to guess at.
        return fields.takeIf { it["format"] == VERSION }
    }
}
