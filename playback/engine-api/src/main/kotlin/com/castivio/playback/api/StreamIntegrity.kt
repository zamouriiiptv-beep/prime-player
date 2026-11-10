package com.castivio.playback.api

/**
 * Where a stream's bytes stand in [TsScanner]'s reading of them.
 */
enum class IntegrityState {
    /** Nothing is measured: a build that does not measure, or an engine that cannot say. The panel draws nothing for it. */
    OFF,

    /** Measuring, and nothing has arrived yet. */
    NOTHING_YET,

    /** Looking for the stream's packets: a transport stream is recognised by its marker repeating every 188 bytes. */
    SCANNING,

    /** A transport stream, and its packets are being read. */
    TRANSPORT_STREAM,

    /** Not one: a playlist, another container, or bytes with no packet structure. Nothing is counted for it. */
    NOT_TRANSPORT_STREAM,

    /** Not read by this application at all: the figures are the playback library's own account of its demuxer. */
    DEMUXER,
}

/**
 * **Temporary, for development.** What the bytes of a stream that reached the player say about themselves: whether
 * they arrived whole.
 *
 * A picture that breaks up in blocks for a second or two and then mends is almost always a stream that lost a piece:
 * the decoder, handed a frame with part of it missing, draws what it can and every frame that leans on that one is
 * wrong until the next key frame. The network figures of an opening cannot say so, because the bytes arrived: a
 * connection that delivers every byte it is given says nothing of the ones the provider never gave it. A transport
 * stream says it itself. Each packet carries a four-bit counter that goes up by one for its stream, so a packet that
 * did not arrive is a counter that jumped; and a packet its sender knew to be damaged is flagged as such.
 *
 * Every instant is milliseconds after the engine was asked to open the source, like [OpeningMarks]'s own.
 */
data class StreamIntegrity(
    val state: IntegrityState = IntegrityState.OFF,
    /** Bytes handed to the scanner, from the start of the connection that is read now and those before it. */
    val bytes: Long = 0,
    /** Packets read, null packets (the padding of a constant-rate stream) not counted. */
    val packets: Long = 0,
    /** The packet identifier of the video, once the stream's table said which it is. */
    val videoPid: Int? = null,
    /** Counter jumps on any stream. Each is at least one packet that did not arrive, or a source that began again. */
    val gaps: Int = 0,
    /** Counter jumps on the video's own packets: the ones a picture breaks up on. Zero while the video is not known. */
    val gapsOnVideo: Int = 0,
    /** At least this many packets were lost: a jump of four bits cannot say that a multiple of sixteen went missing. */
    val lostAtLeast: Long = 0,
    /** The same, for the video's packets alone. */
    val lostOnVideoAtLeast: Long = 0,
    /** Packets their sender flagged as damaged: the player drops them, and the packet after shows as a gap. */
    val flaggedDamaged: Int = 0,
    /** Times the packets' alignment was lost and had to be found again. */
    val syncLosses: Int = 0,
    /** Bytes passed over while it was found again. */
    val syncSkippedBytes: Long = 0,
    /** Packets that repeated the one before, which the specification allows once. */
    val repeated: Int = 0,
    /** Times the connection was made again after the first. A live stream that is joined again starts mid-picture. */
    val reconnects: Int = 0,
    /** The first time anything was found wrong. */
    val firstDamageMs: Long? = null,
    /** The last time anything was found wrong. */
    val lastDamageMs: Long? = null,
    /** The last counter jump on the video's packets. */
    val lastVideoGapMs: Long? = null,
    /** The last time the connection was made again. */
    val lastReconnectMs: Long? = null,
    /** The demuxer's own count of packets it called corrupt, for [IntegrityState.DEMUXER]. */
    val corrupted: Int = 0,
    /** When this was read, on the same clock as the instants above: what "ago" is counted from. */
    val atMs: Long = 0,
) {

    /** Whether anything at all was found wrong: a flag, a jump, a loss of alignment, or a demuxer's complaint. */
    val damaged: Boolean get() = gaps > 0 || flaggedDamaged > 0 || syncLosses > 0 || corrupted > 0

    /** Whether the video itself lost packets: the finding that explains a broken picture. */
    val videoDamaged: Boolean get() = gapsOnVideo > 0 || flaggedDamaged > 0 || syncLosses > 0 || corrupted > 0

    companion object {
        val NONE = StreamIntegrity()

        /** What LibVLC's demuxer says of the stream it reads: it counts, we do not read. */
        fun ofDemuxer(corrupted: Int, discontinuities: Int, atMs: Long): StreamIntegrity = StreamIntegrity(
            state = IntegrityState.DEMUXER,
            gaps = discontinuities,
            corrupted = corrupted,
            atMs = atMs,
        )
    }
}

/**
 * **Temporary, for development.** Reads a MPEG transport stream as it passes, and keeps count of what is wrong with it.
 *
 * Fed the bytes in the order they arrive, in pieces of any size: the result does not depend on how they were cut,
 * which a test holds. It looks at a packet's header and no more -- four bytes of each 188 -- plus the two small tables
 * that say which stream is the video, so it is cheap enough to run beside a player in a build that measures, and is
 * never run in one that does not.
 *
 * What it counts, and why:
 *
 *  - **Counter jumps.** Each stream's packets carry a counter that rises by one, modulo sixteen, with every packet that
 *    has a payload. A jump is a packet lost on the way: by the provider's server, which sometimes drops what a slow
 *    reader does not take; by the provider's own source, which is received over the air; or by a connection that was
 *    made again in the middle of a live stream. A repeated counter is allowed once and is not a loss; a packet whose
 *    header says its stream has a discontinuity is not a loss either, nor is the first packet of a stream.
 *  - **Flagged packets.** A packet the sender knew to be damaged says so. A player drops it, and so the packet after it
 *    shows as a jump too: both are counted, because they are two ways of reading one fact.
 *  - **Lost alignment.** A packet begins with a marker, and one that does not is bytes that did not belong. The scanner
 *    finds the next marker that another follows 188 bytes later, and says how many bytes it passed over.
 *
 * It decides nothing, changes nothing and remembers no address: the packet identifiers it keeps are numbers of a
 * stream's parts. Methods take a lock, because the loader's thread feeds it while the panel's thread reads it.
 */
class TsScanner {

    private var state = IntegrityState.NOTHING_YET

    /** The first bytes of a stream, held until their alignment is known. */
    private val pre = ByteArray(PRELOCK_BYTES)
    private var preLen = 0

    /** The beginning of a packet that the last piece ended in the middle of. */
    private val carry = ByteArray(PACKET)
    private var carryLen = 0

    /** Alignment was lost, and the next marker is being looked for. */
    private var resyncing = false

    private var bytes = 0L
    private var packets = 0L
    private var gaps = 0
    private var lost = 0L
    private var flagged = 0
    private var syncLosses = 0
    private var syncSkipped = 0L
    private var repeated = 0
    private var opens = 0
    private var reconnects = 0
    private var firstDamageMs = NOT_YET
    private var lastDamageMs = NOT_YET
    private var lastVideoGapMs = NOT_YET
    private var lastReconnectMs = NOT_YET

    private val lastCounter = ByteArray(PIDS) { NO_COUNTER }
    private val gapsByPid = IntArray(PIDS)
    private val lostByPid = IntArray(PIDS)

    private var videoPid = NO_PID
    private var pmtPid = NO_PID
    private val patSection = Section()
    private val pmtSection = Section()

    /** A new connection was made for the stream: its bytes begin at an alignment nobody knows. */
    @Synchronized
    fun opened(nowMs: Long) {
        opens++
        if (opens > 1) {
            reconnects++
            lastReconnectMs = nowMs
        }
        preLen = 0
        carryLen = 0
        resyncing = false
        lastCounter.fill(NO_COUNTER)
        if (state != IntegrityState.NOT_TRANSPORT_STREAM) state = IntegrityState.NOTHING_YET
    }

    /** [length] bytes of the stream, from [offset], that arrived at [nowMs]. */
    @Synchronized
    fun feed(data: ByteArray, offset: Int, length: Int, nowMs: Long) {
        if (length <= 0) return
        bytes += length
        when (state) {
            IntegrityState.OFF, IntegrityState.NOT_TRANSPORT_STREAM, IntegrityState.DEMUXER -> Unit
            IntegrityState.NOTHING_YET, IntegrityState.SCANNING -> acquire(data, offset, length, nowMs)
            IntegrityState.TRANSPORT_STREAM -> scan(data, offset, offset + length, nowMs)
        }
    }

    /** What has been found, as it stands at [nowMs]. */
    @Synchronized
    fun snapshot(nowMs: Long): StreamIntegrity = StreamIntegrity(
        state = state,
        bytes = bytes,
        packets = packets,
        videoPid = videoPid.takeIf { it != NO_PID },
        gaps = gaps,
        gapsOnVideo = if (videoPid != NO_PID) gapsByPid[videoPid] else 0,
        lostAtLeast = lost,
        lostOnVideoAtLeast = if (videoPid != NO_PID) lostByPid[videoPid].toLong() else 0L,
        flaggedDamaged = flagged,
        syncLosses = syncLosses,
        syncSkippedBytes = syncSkipped,
        repeated = repeated,
        reconnects = reconnects,
        firstDamageMs = firstDamageMs.takeIf { it != NOT_YET },
        lastDamageMs = lastDamageMs.takeIf { it != NOT_YET },
        lastVideoGapMs = lastVideoGapMs.takeIf { it != NOT_YET },
        lastReconnectMs = lastReconnectMs.takeIf { it != NOT_YET },
        atMs = nowMs,
    )

    /**
     * The first bytes: held until [PRELOCK_BYTES] of them can say where the packets are. The marker has to repeat at
     * [PACKET] intervals [LOCK_MARKERS] times -- the test a player's own reader makes -- and if no alignment does, this is
     * not a transport stream, and nothing more is read of it.
     */
    private fun acquire(data: ByteArray, offset: Int, length: Int, nowMs: Long) {
        state = IntegrityState.SCANNING
        val take = minOf(length, PRELOCK_BYTES - preLen)
        System.arraycopy(data, offset, pre, preLen, take)
        preLen += take
        if (preLen < PRELOCK_BYTES) return
        val start = alignment()
        if (start < 0) {
            state = IntegrityState.NOT_TRANSPORT_STREAM
            preLen = 0
            return
        }
        state = IntegrityState.TRANSPORT_STREAM
        val held = preLen
        preLen = 0
        scan(pre, start, held, nowMs)
        if (length > take) scan(data, offset + take, offset + length, nowMs)
    }

    /** Where in the held bytes a packet begins, judged by the marker coming round [LOCK_MARKERS] times, or -1. */
    private fun alignment(): Int {
        for (start in 0 until PACKET) {
            var all = true
            for (n in 0 until LOCK_MARKERS) {
                if (pre[start + n * PACKET].toInt() != MARKER) {
                    all = false
                    break
                }
            }
            if (all) return start
        }
        return -1
    }

    private fun scan(data: ByteArray, start: Int, end: Int, nowMs: Long) {
        var i = start
        while (i < end) {
            if (carryLen > 0) {
                val take = minOf(PACKET - carryLen, end - i)
                System.arraycopy(data, i, carry, carryLen, take)
                carryLen += take
                i += take
                if (carryLen == PACKET) {
                    carryLen = 0
                    packet(carry, 0, nowMs)
                }
                continue
            }
            if (resyncing) {
                val found = findMarker(data, i, end)
                if (found < 0) {
                    syncSkipped += end - i
                    i = end
                    continue
                }
                syncSkipped += found - i
                i = found
                resyncing = false
            }
            if (data[i].toInt() != MARKER) {
                syncLosses++
                damage(nowMs)
                resyncing = true
                lastCounter.fill(NO_COUNTER)
                continue
            }
            if (end - i >= PACKET) {
                packet(data, i, nowMs)
                i += PACKET
            } else {
                System.arraycopy(data, i, carry, 0, end - i)
                carryLen = end - i
                i = end
            }
        }
    }

    /** The next place a packet could begin: a marker that another follows a packet later, or that the piece ends before. */
    private fun findMarker(data: ByteArray, from: Int, end: Int): Int {
        for (j in from until end) {
            if (data[j].toInt() == MARKER && (j + PACKET >= end || data[j + PACKET].toInt() == MARKER)) return j
        }
        return -1
    }

    private fun packet(buf: ByteArray, off: Int, nowMs: Long) {
        val b1 = buf[off + 1].toInt() and 0xFF
        val b2 = buf[off + 2].toInt() and 0xFF
        val b3 = buf[off + 3].toInt() and 0xFF
        val pid = ((b1 and 0x1F) shl 8) or b2
        // The padding of a constant-rate stream: it has no counter, and it is not the stream.
        if (pid == NULL_PID) return
        packets++
        if ((b1 and 0x80) != 0) {
            // Skipped without its counter being taken, as a player reads it: so the packet after shows as a jump.
            flagged++
            damage(nowMs)
            return
        }
        val control = (b3 shr 4) and 0x3
        if (control == 0) return
        val counter = b3 and 0x0F
        val hasPayload = (control and 0x1) != 0
        if (!hasPayload) return
        var discontinuity = false
        if ((control and 0x2) != 0) {
            val adaptationLength = buf[off + 4].toInt() and 0xFF
            if (adaptationLength in 1..MAX_ADAPTATION) discontinuity = (buf[off + 5].toInt() and 0x80) != 0
        }
        val previous = lastCounter[pid].toInt()
        when {
            previous < 0 || discontinuity -> lastCounter[pid] = counter.toByte()
            counter == previous -> repeated++
            counter != ((previous + 1) and 0x0F) -> {
                val missing = (counter - previous - 1) and 0x0F
                gaps++
                lost += missing
                gapsByPid[pid]++
                lostByPid[pid] += missing
                lastCounter[pid] = counter.toByte()
                damage(nowMs)
                if (pid == videoPid) lastVideoGapMs = nowMs
            }
            else -> lastCounter[pid] = counter.toByte()
        }
        if (pid == PAT_PID || pid == pmtPid) table(pid, buf, off, b1, control)
    }

    private fun damage(nowMs: Long) {
        if (firstDamageMs == NOT_YET) firstDamageMs = nowMs
        lastDamageMs = nowMs
    }

    /** The program association and program map tables, which say where the video is: one section, put together from its packets. */
    private fun table(pid: Int, buf: ByteArray, off: Int, b1: Int, control: Int) {
        var p = off + 4
        if ((control and 0x2) != 0) p += 1 + (buf[off + 4].toInt() and 0xFF)
        val end = off + PACKET
        if (p >= end) return
        val section = if (pid == PAT_PID) patSection else pmtSection
        if ((b1 and 0x40) != 0) {
            p += 1 + (buf[p].toInt() and 0xFF)
            if (p >= end) {
                section.reset()
                return
            }
            section.begin()
        } else if (!section.active) {
            return
        }
        section.add(buf, p, end)
        if (section.complete) {
            if (pid == PAT_PID) readAssociation(section) else readProgramMap(section)
            section.reset()
        }
    }

    private fun readAssociation(section: Section) {
        val d = section.data
        if (d[0].toInt() != TABLE_PAT) return
        var p = 8
        val end = section.length - CRC_BYTES
        while (p + 4 <= end) {
            val program = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            val pid = ((d[p + 2].toInt() and 0x1F) shl 8) or (d[p + 3].toInt() and 0xFF)
            // Program zero is the network table. The first program that is not is the one read.
            if (program != 0) {
                if (pmtPid != pid) {
                    pmtPid = pid
                    pmtSection.reset()
                }
                return
            }
            p += 4
        }
    }

    private fun readProgramMap(section: Section) {
        val d = section.data
        if (d[0].toInt() != TABLE_PMT) return
        val infoLength = ((d[10].toInt() and 0x0F) shl 8) or (d[11].toInt() and 0xFF)
        var p = 12 + infoLength
        val end = section.length - CRC_BYTES
        while (p + 5 <= end) {
            val type = d[p].toInt() and 0xFF
            val pid = ((d[p + 1].toInt() and 0x1F) shl 8) or (d[p + 2].toInt() and 0xFF)
            val streamInfoLength = ((d[p + 3].toInt() and 0x0F) shl 8) or (d[p + 4].toInt() and 0xFF)
            if (isVideo(type)) {
                videoPid = pid
                return
            }
            p += 5 + streamInfoLength
        }
    }

    /** One table section, put together from the payloads of the packets it came in. */
    private class Section {
        val data = ByteArray(MAX_SECTION)
        private var have = 0
        private var expected = 0
        var active = false
            private set

        /** The section's whole length once its header has been read: three bytes of header and what they count. */
        val length: Int get() = expected

        val complete: Boolean get() = active && expected > 0 && have >= expected

        fun begin() {
            have = 0
            expected = 0
            active = true
        }

        fun reset() {
            have = 0
            expected = 0
            active = false
        }

        fun add(buf: ByteArray, from: Int, to: Int) {
            val take = minOf(to - from, MAX_SECTION - have)
            if (take <= 0) {
                reset()
                return
            }
            System.arraycopy(buf, from, data, have, take)
            have += take
            if (expected == 0 && have >= 3) {
                expected = 3 + (((data[1].toInt() and 0x0F) shl 8) or (data[2].toInt() and 0xFF))
                if (expected > MAX_SECTION || expected < MIN_SECTION) reset()
            }
        }
    }

    companion object {
        const val PACKET = 188
        private const val MARKER = 0x47
        private const val LOCK_MARKERS = 5
        private const val PRELOCK_BYTES = PACKET * 6
        private const val PIDS = 8192
        private const val NULL_PID = 0x1FFF
        private const val PAT_PID = 0
        private const val NO_PID = -1
        private const val NO_COUNTER: Byte = -1
        private const val NOT_YET = -1L
        private const val MAX_ADAPTATION = 183
        private const val TABLE_PAT = 0x00
        private const val TABLE_PMT = 0x02
        private const val CRC_BYTES = 4
        private const val MAX_SECTION = 1024
        private const val MIN_SECTION = 12

        /** Stream types of a video: MPEG-1, MPEG-2, MPEG-4 part 2, H.264, H.265, AVS, VC-1. */
        private fun isVideo(type: Int): Boolean =
            type == 0x01 || type == 0x02 || type == 0x10 || type == 0x1B || type == 0x24 || type == 0x42 || type == 0xEA
    }
}
