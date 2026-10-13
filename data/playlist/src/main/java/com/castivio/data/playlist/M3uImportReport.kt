package com.castivio.data.playlist

import com.castivio.data.networking.RemoteResult
import com.castivio.data.networking.kind
import com.castivio.domain.ImportSummary

/**
 * One line saying how a playlist download ended, for `adb logcat -s CastivioNet`.
 *
 * ## Why it exists
 *
 * Because an M3U import that failed used to leave no trace at all. The status, the
 * exception and the stage were handed to the writer's `abort`, which ignored them, and
 * the screen said "your provider did not answer" whatever had happened — so a refused
 * request, an unreachable server, a slow one and an unreadable answer were the same
 * sentence and nobody could say which of the four a user was looking at.
 *
 * ## What is in it, and what never is
 *
 * The status, the content type, the kind of failure, the exception's class, how long it
 * took and how much arrived. **Never the address.** An M3U link carries the account's
 * username and password in its query, and a log is the first thing a user pastes into a
 * support message. Not the exception's *message* either, for the same reason: an unknown
 * host, a refused cleartext request and most TLS errors name the host, and some name the
 * whole URL. The scheme is the only part of the address that is written, and only as
 * `http` or `https`, because whether a link was plain HTTP is itself a finding.
 */
internal object M3uImportReport {

    fun line(
        result: RemoteResult<ImportSummary>,
        scheme: String?,
        tookMs: Long,
        receivedBytes: Long?,
    ): String = buildString {
        append("M3U import ")
        when (result) {
            is RemoteResult.Success -> {
                append("ok")
                append(" http=").append(result.statusCode)
                append(" type=").append(mediaType(result.contentType))
                append(" items=").append(result.value.items)
            }

            is RemoteResult.NotModified -> append("not-modified http=304")

            is RemoteResult.Failure -> {
                append("failed")
                append(" kind=").append(result.kind.name)
                append(" error=").append(result.error.name)
                append(" http=").append(result.statusCode?.toString() ?: NONE)
                append(" type=").append(mediaType(result.contentType))
                append(" stage=").append(result.stage.name.lowercase())
                append(" exception=").append(exceptionName(result.cause))
            }
        }
        if (receivedBytes != null) append(" bytes=").append(receivedBytes)
        append(" scheme=").append(scheme?.takeIf { it == "http" || it == "https" } ?: NONE)
        append(" took=").append(tookMs).append("ms")
    }

    /** `http` or `https`, and nothing else of the address. */
    fun schemeOf(url: String): String? =
        url.trim().substringBefore("://", missingDelimiterValue = "").lowercase()
            .takeIf { it == "http" || it == "https" }

    /**
     * The media type alone — `text/html`, not its parameters — and only if it looks like
     * one. A header is provider-controlled text, and this line must not become a place
     * where a provider can write whatever it likes into a user's log.
     */
    private fun mediaType(raw: String?): String {
        val type = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val wellFormed = type.isNotEmpty() &&
            type.length <= MAX_TYPE &&
            type.all { it in 'a'..'z' || it in '0'..'9' || it in TYPE_PUNCTUATION }
        return if (wellFormed) type else NONE
    }

    /**
     * The exception's class, and its root cause's when that is a different one —
     * `SSLHandshakeException<-CertPathValidatorException` says what a bare handshake
     * failure does not. Class names only; see the class note for why never a message.
     */
    private fun exceptionName(cause: Throwable?): String {
        if (cause == null) return NONE
        val root = generateSequence(cause) { it.cause }.take(MAX_CAUSES).last()
        val outer = nameOf(cause)
        val inner = nameOf(root)
        return if (root === cause || inner == outer) outer else "$outer<-$inner"
    }

    private fun nameOf(error: Throwable): String =
        error.javaClass.simpleName.ifEmpty { error.javaClass.name.substringAfterLast('.') }

    private const val NONE = "-"
    private const val MAX_TYPE = 64
    private const val MAX_CAUSES = 8
    private const val TYPE_PUNCTUATION = "/+.-"
}
