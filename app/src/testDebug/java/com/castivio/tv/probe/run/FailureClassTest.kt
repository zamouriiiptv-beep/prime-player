package com.castivio.tv.probe.run

import com.castivio.data.parsing.JsonFormatException
import com.castivio.tv.probe.core.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class FailureClassTest {

    @Test
    fun a_timeout_is_a_timeout_whether_the_socket_or_the_read_gave_it() {
        assertEquals(Outcome.TIMEOUT, FailureClass.of(SocketTimeoutException("read timed out")))
        assertEquals(Outcome.TIMEOUT, FailureClass.of(InterruptedIOException("timeout")))
    }

    @Test
    fun a_name_that_does_not_resolve_is_dns() {
        assertEquals(Outcome.DNS, FailureClass.of(UnknownHostException("panel.example")))
    }

    @Test
    fun a_connection_that_could_not_be_made_is_connect_and_not_a_reset() {
        assertEquals(Outcome.CONNECT, FailureClass.of(ConnectException("refused")))
        assertEquals(Outcome.CONNECT, FailureClass.of(NoRouteToHostException("no route")))
    }

    @Test
    fun a_failed_handshake_is_tls() {
        assertEquals(Outcome.TLS, FailureClass.of(SSLException("closed")))
        assertEquals(Outcome.TLS, FailureClass.of(SSLHandshakeException("bad certificate")))
    }

    @Test
    fun a_broken_http_exchange_is_protocol() {
        assertEquals(Outcome.PROTOCOL, FailureClass.of(ProtocolException("unexpected end of stream")))
    }

    @Test
    fun a_connection_that_went_away_is_a_reset() {
        assertEquals(Outcome.RESET, FailureClass.of(SocketException("Connection reset")))
        assertEquals(Outcome.RESET, FailureClass.of(EOFException()))
    }

    @Test
    fun a_reply_that_is_not_what_was_asked_for_is_unreadable() {
        assertEquals(Outcome.UNREADABLE, FailureClass.of(JsonFormatException("expected [")))
    }

    @Test
    fun the_probes_own_endings_have_their_own_names() {
        assertEquals(Outcome.CANCELLED, FailureClass.of(CancelledException()))
        assertEquals(Outcome.OVERSIZE, FailureClass.of(OversizeException()))
    }

    @Test
    fun anything_else_is_other() {
        assertEquals(Outcome.OTHER, FailureClass.of(IllegalStateException("x")))
        assertEquals(Outcome.OTHER, FailureClass.of(IOException("x")))
    }

    @Test
    fun the_message_is_never_read() {
        // A message can carry the host. Naming a failure must not so much as look at it.
        class Hostile : IOException() {
            override val message: String?
                get() = throw AssertionError("the message of a failure must not be read")
        }
        assertEquals(Outcome.OTHER, FailureClass.of(Hostile()))
    }
}
