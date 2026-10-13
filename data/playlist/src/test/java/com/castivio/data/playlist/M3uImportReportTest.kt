package com.castivio.data.playlist

import com.castivio.core.common.AppError
import com.castivio.data.networking.RemoteResult
import com.castivio.domain.ImportSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * The one line a playlist import writes, exactly — because a person diagnosing a user's
 * subscription reads it, and because it must never carry the subscription itself.
 */
class M3uImportReportTest {

    @Test
    fun `a refusal names the status and the type the provider answered with`() {
        val line = M3uImportReport.line(
            RemoteResult.Failure(AppError.UNAUTHORIZED, statusCode = 403, contentType = "text/html; charset=UTF-8"),
            scheme = "https",
            tookMs = 312,
            receivedBytes = null,
        )

        assertEquals(
            "M3U import failed kind=REFUSED error=UNAUTHORIZED http=403 type=text/html " +
                "stage=request exception=- scheme=https took=312ms",
            line,
        )
    }

    @Test
    fun `a timeout is named as one`() {
        val line = M3uImportReport.line(
            RemoteResult.Failure(AppError.TIMEOUT, cause = SocketTimeoutException("timeout")),
            scheme = "http",
            tookMs = 20_004,
            receivedBytes = null,
        )

        assertEquals(
            "M3U import failed kind=TIMEOUT error=TIMEOUT http=- type=- " +
                "stage=request exception=SocketTimeoutException scheme=http took=20004ms",
            line,
        )
    }

    @Test
    fun `a handshake failure names its root cause as well`() {
        val handshake = SSLHandshakeException("handshake failed").apply {
            initCause(CertificateException("certificate expired"))
        }

        val line = M3uImportReport.line(
            RemoteResult.Failure(AppError.NETWORK_UNAVAILABLE, cause = handshake),
            scheme = "https",
            tookMs = 280,
            receivedBytes = null,
        )

        assertEquals(
            "M3U import failed kind=CONNECTION error=NETWORK_UNAVAILABLE http=- type=- " +
                "stage=request exception=SSLHandshakeException<-CertificateException scheme=https took=280ms",
            line,
        )
    }

    @Test
    fun `a body that broke off is a read error with what had arrived`() {
        val line = M3uImportReport.line(
            RemoteResult.Failure(
                AppError.NETWORK_UNAVAILABLE,
                statusCode = 200,
                cause = java.net.ProtocolException("unexpected end of stream"),
                contentType = "audio/x-mpegurl",
                stage = RemoteResult.Stage.BODY,
            ),
            scheme = "http",
            tookMs = 9_100,
            receivedBytes = 1_048_576,
        )

        assertEquals(
            "M3U import failed kind=READ error=NETWORK_UNAVAILABLE http=200 type=audio/x-mpegurl " +
                "stage=body exception=ProtocolException bytes=1048576 scheme=http took=9100ms",
            line,
        )
    }

    @Test
    fun `a playlist that arrived says how much`() {
        val line = M3uImportReport.line(
            RemoteResult.Success(
                value = summary(items = 9_302),
                etag = null,
                lastModified = null,
                contentHash = "crc32:0:0",
                statusCode = 200,
                contentType = "audio/x-mpegurl; charset=utf-8",
            ),
            scheme = "https",
            tookMs = 4_567,
            receivedBytes = 3_145_728,
        )

        assertEquals(
            "M3U import ok http=200 type=audio/x-mpegurl items=9302 bytes=3145728 scheme=https took=4567ms",
            line,
        )
    }

    @Test
    fun `an unchanged playlist is one short line`() {
        val line = M3uImportReport.line(RemoteResult.NotModified, scheme = "https", tookMs = 95, receivedBytes = null)

        assertEquals("M3U import not-modified http=304 scheme=https took=95ms", line)
    }

    @Test
    fun `an exception's message never reaches the log`() {
        // The messages are where hosts and URLs live: an unknown host names itself, and a
        // cleartext refusal names the address it refused.
        val line = M3uImportReport.line(
            RemoteResult.Failure(
                AppError.NETWORK_UNAVAILABLE,
                cause = UnknownHostException("line.example.fyi: get.php?username=sam&password=s3cret"),
            ),
            scheme = "https",
            tookMs = 40,
            receivedBytes = null,
        )

        for (secret in listOf("line.example.fyi", "get.php", "sam", "s3cret", "username", "password")) {
            assertFalse("'$secret' reached the log: $line", line.contains(secret))
        }
    }

    @Test
    fun `a provider cannot write into the log through a header`() {
        val line = M3uImportReport.line(
            RemoteResult.Failure(
                AppError.UNAUTHORIZED,
                statusCode = 403,
                contentType = "text/html\nusername=sam password=s3cret",
            ),
            scheme = "https",
            tookMs = 10,
            receivedBytes = null,
        )

        assertFalse(line, line.contains("sam"))
        assertFalse(line, line.contains("s3cret"))
        assertFalse(line, line.contains("\n"))
    }

    @Test
    fun `only the scheme of an address is kept, and only http or https`() {
        assertEquals("https", M3uImportReport.schemeOf("https://line.example/get.php?username=a&password=b"))
        assertEquals("http", M3uImportReport.schemeOf(" HTTP://line.example/get.php"))
        assertNull(M3uImportReport.schemeOf("ftp://line.example/list.m3u"))
        assertNull(M3uImportReport.schemeOf("line.example/get.php?username=a"))
    }

    private fun summary(items: Int) = ImportSummary(
        sourceId = "src",
        items = items,
        groups = 0,
        skipped = 0,
        byKind = emptyMap(),
        durationMs = 0,
    )
}
