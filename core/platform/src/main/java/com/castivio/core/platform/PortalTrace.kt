package com.castivio.core.platform

/**
 * What each address a portal was asked at answered, in numbers and nothing else -- for a development build to say, on the
 * card of a subscription that was refused, why.
 *
 * A portal that is "nothing at that address" can mean four different things -- no such file, a web server's index page, a
 * portal that does not know the device, a host that did not answer -- and the card says one sentence for all of them.
 * This keeps one line for the last attempt: for each address tried, in order, the HTTP status or what stopped it. **Never
 * an address, a host, a token or a body**: those are the subscription, and a line that carried them could be photographed.
 *
 * Off unless [DevDiagnostics] is on, so a release build keeps nothing and draws nothing of it. Removing it is deleting this
 * file, the two calls in `StalkerHttpApi` and the line on the activation failure card.
 */
object PortalTrace {

    /** How one address answered. */
    sealed interface Answer {

        /** An HTTP status that is not a success. */
        data class Status(val code: Int) : Answer

        /** Answered `200`, and not as a portal: a page, or something that is not the portal's envelope. */
        data object NotAPortal : Answer

        /** Answered as a portal: this is the address that was kept. */
        data object Portal : Answer

        /** Nothing arrived in time. */
        data object TimedOut : Answer

        /** The connection failed before an answer: no route, no host, a reset. */
        data object Unreachable : Answer
    }

    @Volatile
    private var attempts: List<Answer> = emptyList()

    /** Starts the line again, at the first address of a handshake. */
    fun begin() {
        if (DevDiagnostics.enabled) attempts = emptyList()
    }

    /** Adds what the next address answered. */
    fun record(answer: Answer) {
        if (DevDiagnostics.enabled) attempts = attempts + answer
    }

    /** The line for the last handshake, or null when nothing was kept. */
    fun line(): String? = if (DevDiagnostics.enabled) summary(attempts) else null

    /**
     * `1: 404 · 2: 200 not a portal · 3: timeout`, the addresses numbered in the order they were asked, or null for none.
     * English and fixed: a development instrument, read by the person building the app, not part of the product's words.
     */
    internal fun summary(answers: List<Answer>): String? {
        if (answers.isEmpty()) return null
        return answers.withIndex().joinToString(" · ") { (index, answer) ->
            val said = when (answer) {
                is Answer.Status -> answer.code.toString()
                Answer.NotAPortal -> "200 not a portal"
                Answer.Portal -> "portal"
                Answer.TimedOut -> "timeout"
                Answer.Unreachable -> "unreachable"
            }
            "${index + 1}: $said"
        }
    }

    /** For a test: forgets what was kept. */
    internal fun reset() {
        attempts = emptyList()
    }
}
