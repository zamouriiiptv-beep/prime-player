package com.castivio.playback.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How one open's chain of answers is recorded: each hop with its own lookup, connection and wait, a redirect told
 * apart from the answer that carried the stream, and nothing kept of an address but whether it changed.
 *
 * The events are fed in the order the HTTP client reports them, on a clock the test moves by hand.
 */
class HttpHopsTest {

    private var now = 1_000L
    private val clock = { now }

    /** The engine was asked to open at 1 000; every figure the log gives is after that. */
    private fun log(source: String = SOURCE, memory: FinalAddressMemory = FinalAddressMemory()) =
        HopLog(epochMs = 1_000, clock = clock, source = source, memory = memory)

    /**
     * The owner's case: the first address answers on a connection kept open from the last channel, after a long wait,
     * with a redirect to another host written as a number, which has to be connected to and answers quickly.
     */
    @Test
    fun `a redirect on a kept connection, then the stream's server, each hop with its own figures`() {
        val log = log()
        now = 1_020
        log.callStarted()
        log.connectionAcquired()
        log.requestSent("panel.example", secure = false)
        now = 1_406
        log.responseBegan()
        now = 1_410
        log.answered(302, SOURCE)
        now = 1_411
        log.connectStarted()
        now = 1_469
        log.connectEnded()
        log.connectionAcquired()
        log.requestSent("203.0.113.7", secure = false)
        now = 1_553
        log.responseBegan()
        log.answered(200, FINAL)

        val chain = log.snapshot()

        assertEquals(2, chain.count)
        assertEquals(1, chain.redirects)
        val (first, second) = chain.hops
        assertEquals(1, first.number)
        assertEquals(302, first.status)
        assertTrue(first.redirect)
        assertTrue("nothing was connected for it, so the connection was kept open from before", first.reusedConnection)
        assertNull(first.connectMs)
        assertNull(first.dnsMs)
        assertEquals("its own wait is the server's time on it", 386L, first.waitMs)
        assertEquals(20L, first.startedMs)
        assertEquals(390L, first.tookMs)
        assertFalse(first.otherHost)
        assertFalse(first.numericHost)

        assertEquals(2, second.number)
        assertEquals(200, second.status)
        assertFalse(second.redirect)
        assertFalse(second.reusedConnection)
        assertEquals(58L, second.connectMs)
        assertEquals(84L, second.waitMs)
        assertEquals("it begins with the first thing done for it, the connection", 411L, second.startedMs)
        assertEquals(142L, second.tookMs)
        assertTrue("the redirect led elsewhere", second.otherHost)
        assertTrue("a host written as a number has no name to look up", second.numericHost)
    }

    @Test
    fun `an address answered directly is one hop, with its lookup and its TLS inside the connection`() {
        val log = log()
        log.callStarted()
        log.dnsStarted()
        now += 12
        log.dnsEnded()
        log.connectStarted()
        now += 30
        log.secureStarted()
        now += 40
        log.secureEnded()
        now += 2
        log.connectEnded()
        log.connectionAcquired()
        log.requestSent("example.com", secure = true)
        now += 90
        log.responseBegan()
        log.answered(200, SOURCE)

        val hop = log.snapshot().hops.single()

        assertEquals(12L, hop.dnsMs)
        assertEquals(72L, hop.connectMs)
        assertEquals(40L, hop.tlsMs)
        assertEquals(90L, hop.waitMs)
        assertTrue(hop.secure)
        assertFalse(hop.reusedConnection)
        assertFalse(hop.otherHost)
        assertEquals(0, log.snapshot().redirects)
    }

    @Test
    fun `a redirect to the same host on the same connection is not said to go elsewhere`() {
        val log = log()
        log.callStarted()
        log.connectionAcquired()
        log.requestSent("Example.com", secure = false)
        log.responseBegan()
        log.answered(301, SOURCE)
        log.connectionAcquired()
        log.requestSent("example.com", secure = false)
        log.responseBegan()
        log.answered(200, FINAL)

        val (_, second) = log.snapshot().hops

        assertFalse("a host's case is not a different host", second.otherHost)
        assertTrue(second.reusedConnection)
    }

    @Test
    fun `a connection that failed is counted, and the one that worked is the hop's connection`() {
        val log = log()
        log.callStarted()
        log.connectStarted()
        now += 3_000
        log.connectFailed()
        log.connectStarted()
        now += 60
        log.connectEnded()
        log.connectionAcquired()
        log.requestSent("example.com", secure = false)

        val hop = log.snapshot().hops.single()

        assertEquals(1, hop.failedConnects)
        assertEquals("the failed connection's time is part of the hop's", 3_060L, hop.connectMs)
        assertFalse(hop.reusedConnection)
        assertNull("not answered yet", hop.answeredMs)
        assertNull(hop.tookMs)
    }

    @Test
    fun `a hop that fails before an answer says how, by name`() {
        val log = log()
        log.callStarted()
        log.connectionAcquired()
        log.requestSent("example.com", secure = false)
        log.failed("SocketTimeoutException")

        val hop = log.snapshot().hops.single()

        assertEquals("SocketTimeoutException", hop.failure)
        assertNull(hop.status)
    }

    @Test
    fun `a new call is a new try, and its chain replaces the last one's`() {
        val log = log()
        log.callStarted()
        log.connectionAcquired()
        log.requestSent("example.com", secure = false)
        log.failed("SocketTimeoutException")
        log.callStarted()
        log.connectionAcquired()
        log.requestSent("example.com", secure = false)
        log.answered(200, SOURCE)

        val chain = log.snapshot()

        assertEquals(1, chain.count)
        assertNull(chain.hops.single().failure)
        assertEquals(1, chain.hops.single().number)
    }

    @Test
    fun `a chain that loops is counted past what is kept`() {
        val log = log()
        log.callStarted()
        repeat(HopLog.MAX_KEPT + 3) {
            log.connectionAcquired()
            log.requestSent("example.com", secure = false)
            log.answered(302, SOURCE)
        }

        val chain = log.snapshot()

        assertEquals(HopLog.MAX_KEPT, chain.hops.size)
        assertEquals(3, chain.more)
        assertEquals(HopLog.MAX_KEPT + 3, chain.count)
        assertNull("a chain that never reached an answer has no end to compare", chain.finalAddress)
    }

    // ------------------------------------------------------------- the end of the chain, from one open to the next

    @Test
    fun `the first open of an address has nothing to compare its end with`() {
        val log = log()
        log.callStarted()
        log.connectionAcquired()
        log.requestSent("example.com", secure = false)
        log.answered(302, SOURCE)
        assertNull("a redirect is not the end", log.snapshot().finalAddress)
        log.connectionAcquired()
        log.requestSent("203.0.113.7", secure = false)
        log.answered(200, FINAL)

        assertEquals(FinalAddress(sameAsLast = null, lastSeenAgoMs = null), log.snapshot().finalAddress)
    }

    @Test
    fun `the next open of the same address says whether it ended at the same place, and how long ago the last did`() {
        val memory = FinalAddressMemory()
        open(memory, final = FINAL)
        now += 180_000
        val same = open(memory, final = FINAL)
        now += 5_000
        val changed = open(memory, final = "$FINAL-next")

        assertEquals(FinalAddress(sameAsLast = true, lastSeenAgoMs = 180_000), same)
        assertEquals(FinalAddress(sameAsLast = false, lastSeenAgoMs = 5_000), changed)
    }

    @Test
    fun `two addresses are remembered apart`() {
        val memory = FinalAddressMemory()
        open(memory, source = SOURCE, final = FINAL)
        val other = open(memory, source = "$SOURCE/other", final = FINAL)

        assertNull("another channel's first open compares with nothing", other?.sameAsLast)
    }

    @Test
    fun `the memory keeps the addresses opened last, and no more`() {
        val memory = FinalAddressMemory(capacity = 2)
        memory.remember(1, 10, 0)
        memory.remember(2, 20, 0)
        memory.remember(3, 30, 0)

        assertEquals(2, memory.size())
        assertNull("the oldest was let go", memory.remember(1, 10, 0).sameAsLast)
    }

    // ------------------------------------------------------------------------ what is said of a host

    @Test
    fun `a host written as a number is told from a name`() {
        assertTrue(HopLog.isNumericHost("203.0.113.7"))
        assertTrue(HopLog.isNumericHost("2001:db8::1"))
        assertTrue(HopLog.isNumericHost("[2001:db8::1]"))
        assertFalse(HopLog.isNumericHost("example.com"))
        assertFalse("a name may begin with digits", HopLog.isNumericHost("123.example"))
        assertFalse(HopLog.isNumericHost("1.2.3"))
        assertFalse(HopLog.isNumericHost("256.1.1.1"))
        assertFalse(HopLog.isNumericHost(""))
    }

    @Test
    fun `a fingerprint is the same for the same address and differs for another`() {
        assertEquals(HopLog.fingerprint(FINAL), HopLog.fingerprint(FINAL))
        assertNotEquals(HopLog.fingerprint(FINAL), HopLog.fingerprint("$FINAL?"))
        assertNotEquals(HopLog.fingerprint("ab"), HopLog.fingerprint("ba"))
    }

    /** One open of [source] whose chain ends at [final] after a redirect; what it said of the end. */
    private fun open(memory: FinalAddressMemory, source: String = SOURCE, final: String): FinalAddress? {
        val log = HopLog(epochMs = now, clock = clock, source = source, memory = memory)
        log.callStarted()
        log.connectionAcquired()
        log.requestSent("example.com", secure = false)
        log.answered(302, source)
        log.connectionAcquired()
        log.requestSent("203.0.113.7", secure = false)
        log.answered(200, final)
        return log.snapshot().finalAddress
    }

    private companion object {
        const val SOURCE = "http://example.com:8080/live/user/pass/1.ts"
        const val FINAL = "http://203.0.113.7:8080/live/token/1.ts"
    }
}
