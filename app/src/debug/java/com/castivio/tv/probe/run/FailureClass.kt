package com.castivio.tv.probe.run

import com.castivio.data.parsing.JsonFormatException
import com.castivio.tv.probe.core.Outcome
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Names how a request failed, from the class of what it threw.
 *
 * The message is never read: an exception's text can carry the host, and a report must not. The order of the
 * branches is the order of the class hierarchy -- a connect failure is a socket failure, a timeout is an
 * interrupted read -- so the narrower name is asked first.
 */
object FailureClass {

    fun of(error: Throwable): Outcome = when (error) {
        is OversizeException -> Outcome.OVERSIZE
        is CancelledException -> Outcome.CANCELLED
        is SocketTimeoutException, is InterruptedIOException -> Outcome.TIMEOUT
        is UnknownHostException -> Outcome.DNS
        is ConnectException, is NoRouteToHostException -> Outcome.CONNECT
        is SSLException -> Outcome.TLS
        is ProtocolException -> Outcome.PROTOCOL
        is SocketException, is EOFException -> Outcome.RESET
        is JsonFormatException -> Outcome.UNREADABLE
        else -> Outcome.OTHER
    }
}
