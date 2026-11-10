package com.castivio.playback.api

import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a transport stream says of its own integrity, read from synthetic streams whose every packet is made here so that
 * each case has one right answer: a packet missing, a packet flagged, bytes that do not belong, a stream that is not one.
 */
class TsScannerTest {

    private val video = 0x100
    private val audio = 0x101
    private val pmt = 0x1000

    // --------------------------------------------------------------------------------------------- clean streams

    @Test
    fun `a stream whose counters rise by one has nothing wrong`() {
        val scanner = TsScanner()
        scanner.feed(stream(preamble() + counted(video, 0, 200) + counted(audio, 0, 100)))

        val seen = scanner.snapshot(NOW)

        assertEquals(IntegrityState.TRANSPORT_STREAM, seen.state)
        assertEquals(0, seen.gaps)
        assertEquals(0, seen.flaggedDamaged)
        assertEquals(0, seen.syncLosses)
        assertFalse(seen.damaged)
        assertEquals("every packet but the padding is counted", 2L + 200 + 100, seen.packets)
        assertEquals("the video is found from the tables, not guessed", video, seen.videoPid)
    }

    @Test
    fun `the padding of a constant-rate stream is not a stream and is not counted`() {
        val scanner = TsScanner()
        scanner.feed(stream(preamble() + counted(video, 0, 50) + List(40) { packet(0x1FFF, 0) } + counted(video, 50, 50)))

        val seen = scanner.snapshot(NOW)

        assertEquals(0, seen.gaps)
        assertEquals(2L + 100, seen.packets)
    }

    @Test
    fun `the counter wraps from fifteen to zero without a jump`() {
        val scanner = TsScanner()
        scanner.feed(stream(preamble() + counted(video, 12, 10)))

        assertEquals(0, scanner.snapshot(NOW).gaps)
    }

    @Test
    fun `a packet with an adaptation field and no payload does not move the counter`() {
        val scanner = TsScanner()
        val list = preamble() + counted(video, 0, 5) +
            // Carries the counter of the packet before it, as the specification has it: it brings no payload.
            listOf(packet(video, 4, control = ADAPTATION_ONLY)) + counted(video, 5, 5)
        scanner.feed(stream(list))

        val seen = scanner.snapshot(NOW)

        assertEquals(0, seen.gaps)
        assertEquals("and it is not read as a packet repeated", 0, seen.repeated)
    }

    @Test
    fun `a repeated packet is allowed once and is not a loss`() {
        val scanner = TsScanner()
        val list = preamble() + counted(video, 0, 5) + listOf(packet(video, 4)) + counted(video, 5, 5)
        scanner.feed(stream(list))

        val seen = scanner.snapshot(NOW)

        assertEquals(0, seen.gaps)
        assertEquals(1, seen.repeated)
    }

    @Test
    fun `a jump the header says is meant is not a loss`() {
        val scanner = TsScanner()
        val list = preamble() + counted(video, 0, 5) +
            listOf(packet(video, 9, control = ADAPTATION_AND_PAYLOAD, discontinuity = true)) + counted(video, 10, 5)
        scanner.feed(stream(list))

        assertEquals(0, scanner.snapshot(NOW).gaps)
    }

    // ------------------------------------------------------------------------------------------- lost packets

    @Test
    fun `one packet missing from the video is one gap on the video`() {
        val scanner = TsScanner()
        val all = counted(video, 0, 100)
        scanner.feed(stream(preamble() + all.filterIndexed { index, _ -> index != 40 }), 1_234)

        val seen = scanner.snapshot(NOW)

        assertEquals(1, seen.gaps)
        assertEquals(1, seen.gapsOnVideo)
        assertEquals(1L, seen.lostAtLeast)
        assertEquals(1L, seen.lostOnVideoAtLeast)
        assertTrue(seen.damaged)
        assertTrue(seen.videoDamaged)
        assertEquals(1_234L, seen.lastVideoGapMs)
        assertEquals(1_234L, seen.firstDamageMs)
    }

    @Test
    fun `how many are missing is the size of the jump`() {
        val scanner = TsScanner()
        val all = counted(video, 0, 100)
        // Three in a row: the counter goes from 7 to 11, so three packets were lost.
        scanner.feed(stream(preamble() + all.filterIndexed { index, _ -> index !in 8..10 }))

        val seen = scanner.snapshot(NOW)

        assertEquals(1, seen.gaps)
        assertEquals(3L, seen.lostAtLeast)
    }

    @Test
    fun `a loss across the wrap is found`() {
        val scanner = TsScanner()
        val all = counted(video, 0, 40)
        // The packet whose counter is 0, after the 15 before it.
        scanner.feed(stream(preamble() + all.filterIndexed { index, _ -> index != 16 }))

        assertEquals(1L, scanner.snapshot(NOW).lostAtLeast)
    }

    @Test
    fun `a loss on the audio is a gap and not a gap on the video`() {
        val scanner = TsScanner()
        val sound = counted(audio, 0, 50)
        scanner.feed(stream(preamble() + counted(video, 0, 50) + sound.filterIndexed { index, _ -> index != 20 }))

        val seen = scanner.snapshot(NOW)

        assertEquals(1, seen.gaps)
        assertEquals("a picture does not break up for lost sound", 0, seen.gapsOnVideo)
        assertFalse(seen.videoDamaged)
        assertTrue(seen.damaged)
    }

    @Test
    fun `losses are counted every time and the last is remembered`() {
        val scanner = TsScanner()
        scanner.feed(stream(preamble() + counted(video, 0, 20).filterIndexed { index, _ -> index != 5 }), 100)
        scanner.feed(stream(counted(video, 20, 20).filterIndexed { index, _ -> index != 7 }), 900)

        val seen = scanner.snapshot(1_000)

        assertEquals(2, seen.gapsOnVideo)
        assertEquals(100L, seen.firstDamageMs)
        assertEquals(900L, seen.lastVideoGapMs)
        assertEquals(1_000L, seen.atMs)
    }

    @Test
    fun `a packet flagged as damaged is counted and the packet after it shows as a jump`() {
        val scanner = TsScanner()
        val list = preamble() + counted(video, 0, 10) + listOf(packet(video, 10, damaged = true)) + counted(video, 11, 10)
        scanner.feed(stream(list), 700)

        val seen = scanner.snapshot(NOW)

        assertEquals(1, seen.flaggedDamaged)
        assertEquals("read as a player reads it: the flagged packet did not give its counter", 1, seen.gapsOnVideo)
        assertEquals(700L, seen.lastDamageMs)
    }

    // ---------------------------------------------------------------------------------------- alignment

    @Test
    fun `bytes before the first packet are skipped and are not damage`() {
        val scanner = TsScanner()
        val junk = ByteArray(77) { 0x11 }
        scanner.feed(junk + stream(preamble() + counted(video, 0, 100)))

        val seen = scanner.snapshot(NOW)

        assertEquals(IntegrityState.TRANSPORT_STREAM, seen.state)
        assertEquals(0, seen.syncLosses)
        assertEquals(0, seen.gaps)
    }

    @Test
    fun `bytes that do not belong in the middle lose the alignment once and it is found again`() {
        val scanner = TsScanner()
        val garbage = ByteArray(50) { 0x22 }
        val data = stream(preamble() + counted(video, 0, 100))
        val cut = 188 * 40
        scanner.feed(data.copyOfRange(0, cut) + garbage + data.copyOfRange(cut, data.size), 500)

        val seen = scanner.snapshot(NOW)

        assertEquals(1, seen.syncLosses)
        assertEquals(50L, seen.syncSkippedBytes)
        assertEquals("the counters are begun again after a loss, so the packets lost with the bytes are not counted twice", 0, seen.gaps)
        assertTrue(seen.videoDamaged)
        assertEquals(500L, seen.lastDamageMs)
        assertEquals("and the packets after it are read: none was lost, only bytes were added", 2L + 100, seen.packets)
    }

    @Test
    fun `a stream that is not a transport stream is said not to be one and nothing is counted`() {
        val scanner = TsScanner()
        val playlist = ("#EXTM3U\n#EXT-X-VERSION:3\n" + "#EXTINF:6.0,\nsegment-0001.ts\n".repeat(200)).toByteArray()
        scanner.feed(playlist)
        scanner.feed(playlist)

        val seen = scanner.snapshot(NOW)

        assertEquals(IntegrityState.NOT_TRANSPORT_STREAM, seen.state)
        assertEquals(0L, seen.packets)
        assertEquals(0, seen.gaps)
        assertEquals("the bytes are still counted", playlist.size * 2L, seen.bytes)
    }

    @Test
    fun `too little to tell is not a verdict`() {
        val scanner = TsScanner()
        scanner.feed(ByteArray(300) { 0x47 })

        assertEquals(IntegrityState.SCANNING, scanner.snapshot(NOW).state)
    }

    // ---------------------------------------------------------------------------------------- how it is cut

    @Test
    fun `the answer does not depend on how the bytes were cut`() {
        val data = stream(preamble() + counted(video, 0, 300).filterIndexed { index, _ -> index != 111 && index != 222 } + counted(audio, 0, 80))
        val whole = TsScanner().also { it.feed(data) }.snapshot(NOW)

        for (seed in 1L..20L) {
            val random = Random(seed)
            val scanner = TsScanner()
            var at = 0
            while (at < data.size) {
                val piece = 1 + random.nextInt(if (seed % 2 == 0L) 12 else 1500)
                val to = minOf(data.size, at + piece)
                scanner.feed(data, at, to - at, 0)
                at = to
            }
            val cut = scanner.snapshot(NOW)
            assertEquals("cut $seed", whole.copy(firstDamageMs = null, lastDamageMs = null, lastVideoGapMs = null), cut.copy(firstDamageMs = null, lastDamageMs = null, lastVideoGapMs = null))
        }
        assertEquals(2, whole.gapsOnVideo)
    }

    // ---------------------------------------------------------------------------------------- connections

    @Test
    fun `a connection made again is counted, and its first packets are not read as a jump`() {
        val scanner = TsScanner()
        scanner.opened(10)
        scanner.feed(stream(preamble() + counted(video, 0, 50)))
        scanner.opened(2_000)
        // A live stream joined again starts wherever it is: the counters have nothing to do with the last connection's.
        scanner.feed(stream(preamble() + counted(video, 9, 50)))

        val seen = scanner.snapshot(NOW)

        assertEquals(1, seen.reconnects)
        assertEquals(2_000L, seen.lastReconnectMs)
        assertEquals(0, seen.gaps)
        assertEquals(IntegrityState.TRANSPORT_STREAM, seen.state)
    }

    @Test
    fun `the first connection is not a reconnection`() {
        val scanner = TsScanner()
        scanner.opened(0)

        assertEquals(0, scanner.snapshot(NOW).reconnects)
        assertNull(scanner.snapshot(NOW).lastReconnectMs)
    }

    // ---------------------------------------------------------------------------------------- the tables

    @Test
    fun `the video is found whatever order the program lists its streams in`() {
        val scanner = TsScanner()
        // Audio first, then video: a program map does not promise the picture comes first.
        val map = programMap(pmt, listOf(0x0F to audio, 0x1B to video))
        scanner.feed(stream(listOf(association(pmt), map) + counted(video, 0, 20)))

        assertEquals(video, scanner.snapshot(NOW).videoPid)
    }

    @Test
    fun `a video of another kind is found too`() {
        val scanner = TsScanner()
        scanner.feed(stream(listOf(association(pmt), programMap(pmt, listOf(0x03 to audio, 0x24 to 0x200))) + counted(0x200, 0, 20)))

        assertEquals(0x200, scanner.snapshot(NOW).videoPid)
    }

    @Test
    fun `without the tables the video is not known and a loss on it is a gap and not a video gap`() {
        val scanner = TsScanner()
        scanner.feed(stream(counted(video, 0, 100).filterIndexed { index, _ -> index != 30 }))

        val seen = scanner.snapshot(NOW)

        assertNull(seen.videoPid)
        assertEquals(1, seen.gaps)
        assertEquals(0, seen.gapsOnVideo)
    }

    @Test
    fun `nothing measured is off, and a scanner that has been given nothing is measuring`() {
        assertEquals(IntegrityState.OFF, StreamIntegrity.NONE.state)
        assertFalse(StreamIntegrity.NONE.damaged)
        assertEquals(IntegrityState.NOTHING_YET, TsScanner().snapshot(0).state)
    }

    @Test
    fun `the demuxer's own account is damage when it counts any`() {
        assertTrue(StreamIntegrity.ofDemuxer(corrupted = 2, discontinuities = 0, atMs = 5).damaged)
        assertTrue(StreamIntegrity.ofDemuxer(corrupted = 0, discontinuities = 3, atMs = 5).damaged)
        assertFalse(StreamIntegrity.ofDemuxer(corrupted = 0, discontinuities = 0, atMs = 5).damaged)
        assertEquals(IntegrityState.DEMUXER, StreamIntegrity.ofDemuxer(0, 0, 0).state)
    }

    // ------------------------------------------------------------------------------------------- fixtures

    private fun TsScanner.feed(data: ByteArray, nowMs: Long = 0) = feed(data, 0, data.size, nowMs)

    private fun stream(packets: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { out -> packets.forEach { out.write(it) } }.toByteArray()

    /** The two tables that say which stream is the video, as a stream begins with them. */
    private fun preamble(): List<ByteArray> =
        listOf(association(pmt), programMap(pmt, listOf(0x1B to video, 0x0F to audio)))

    /** [count] packets of one stream whose counters begin at [first]. */
    private fun counted(pid: Int, first: Int, count: Int): List<ByteArray> = List(count) { packet(pid, (first + it) and 0x0F) }

    private fun packet(
        pid: Int,
        counter: Int,
        control: Int = PAYLOAD_ONLY,
        damaged: Boolean = false,
        discontinuity: Boolean = false,
        payload: ByteArray? = null,
        startOfUnit: Boolean = false,
    ): ByteArray {
        val p = ByteArray(188) { 0xAA.toByte() }
        p[0] = 0x47
        p[1] = (((if (damaged) 0x80 else 0) or (if (startOfUnit) 0x40 else 0)) or ((pid shr 8) and 0x1F)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = ((control shl 4) or (counter and 0x0F)).toByte()
        var at = 4
        if ((control and 0x2) != 0) {
            // An adaptation field: its length, its flags, and stuffing to the end of the packet when it carries no payload.
            val length = if (control == ADAPTATION_ONLY) 183 else 1
            p[at] = length.toByte()
            p[at + 1] = (if (discontinuity) 0x80 else 0x00).toByte()
            at += 1 + length
        }
        if (payload != null) System.arraycopy(payload, 0, p, at, payload.size)
        return p
    }

    /** A program association table, in one packet, naming [pmtPid] as the program's map. */
    private fun association(pmtPid: Int): ByteArray {
        val section = ByteArrayOutputStream()
        section.write(0x00)                              // table id
        section.write(0xB0); section.write(13)           // syntax indicator, section length: 5 + 4 + 4
        section.write(0x00); section.write(0x01)         // transport stream id
        section.write(0xC1); section.write(0); section.write(0)
        section.write(0x00); section.write(0x01)         // program number
        section.write(0xE0 or ((pmtPid shr 8) and 0x1F)); section.write(pmtPid and 0xFF)
        repeat(4) { section.write(0) }                   // checksum: not read
        return tablePacket(0, section.toByteArray())
    }

    /** A program map table naming each of [streams], as a stream type and the packet identifier it is carried in. */
    private fun programMap(pid: Int, streams: List<Pair<Int, Int>>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x00); body.write(0x01)               // program number
        body.write(0xC1); body.write(0); body.write(0)
        body.write(0xE0 or ((video shr 8) and 0x1F)); body.write(video and 0xFF)   // clock reference
        body.write(0xF0); body.write(0)                  // no program descriptors
        for ((type, streamPid) in streams) {
            body.write(type)
            body.write(0xE0 or ((streamPid shr 8) and 0x1F)); body.write(streamPid and 0xFF)
            body.write(0xF0); body.write(0)              // no stream descriptors
        }
        repeat(4) { body.write(0) }                      // checksum: not read
        val length = body.size()
        val section = ByteArrayOutputStream()
        section.write(0x02)
        section.write(0xB0 or ((length shr 8) and 0x0F)); section.write(length and 0xFF)
        section.write(body.toByteArray())
        return tablePacket(pid, section.toByteArray())
    }

    /** A table's section in one packet: the pointer field, the section, and stuffing to the end. */
    private fun tablePacket(pid: Int, section: ByteArray): ByteArray {
        val payload = ByteArray(184) { 0xFF.toByte() }
        payload[0] = 0
        System.arraycopy(section, 0, payload, 1, section.size)
        return packet(pid, 0, payload = payload, startOfUnit = true)
    }

    private companion object {
        const val NOW = 5_000L
        const val PAYLOAD_ONLY = 1
        const val ADAPTATION_ONLY = 2
        const val ADAPTATION_AND_PAYLOAD = 3
    }
}
