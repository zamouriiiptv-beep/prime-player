package com.castivio.data.parsing

import kotlin.random.Random

/**
 * Which of the directory's servers to ask, in what order, and which to leave alone for a while.
 *
 * The directory is not one address: it is a set of servers run by volunteers, any of which may be down, and the way to use it is to take
 * the names it publishes, pick one, and go on to another when it fails. This is that rule as pure state over a clock, so rotation,
 * cooldown and the refusal of names that are not the directory's are asserted without a network.
 *
 *  - **One server, kept** while it answers: the connection is warm, and every request in a visit goes where the last one did.
 *  - **A server that fails is left alone** for [cooldownMs]; the next one is tried, up to the number a request is allowed.
 *  - **When every server is cooling**, the one whose cooldown ends first is still offered: a list that is empty is a screen that
 *    cannot even try again.
 *  - **Only the directory's own names are accepted** ([accepts]): a name that came out of a lookup is not trusted to be a host the
 *    application should send requests to.
 */
class RadioBrowserServerPool(
    private val clock: () -> Long,
    seed: List<String> = FALLBACK,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val random: Random = Random.Default,
) {
    private var hosts: List<String> = shuffled(seed.filter(::accepts))
    private val cooling = HashMap<String, Long>()
    private var current: String? = null

    /** Replaces the servers with the names just discovered, keeping what is known of the ones that stay. Names refused are dropped. */
    @Synchronized
    fun replace(names: List<String>) {
        val accepted = names.map { it.trim().lowercase() }.filter(::accepts).distinct()
        if (accepted.isEmpty()) return
        hosts = shuffled(accepted)
        cooling.keys.retainAll(accepted.toSet())
        if (current !in accepted) current = null
    }

    /** The names, in the order a request would try them: the kept server first, then those not cooling, then the cooling by when they end. */
    @Synchronized
    fun candidates(max: Int): List<String> {
        require(max > 0) { "max $max" }
        val now = clock()
        val ready = hosts.filter { (cooling[it] ?: 0L) <= now }
        val resting = hosts.filter { (cooling[it] ?: 0L) > now }.sortedBy { cooling[it] }
        val first = current?.takeIf { it in ready }
        val ordered = buildList {
            if (first != null) add(first)
            addAll(ready.filter { it != first })
            addAll(resting)
        }
        return ordered.take(max)
    }

    /** [host] answered: it is the one to keep, and it is not cooling. */
    @Synchronized
    fun succeeded(host: String) {
        if (host !in hosts) return
        current = host
        cooling.remove(host)
    }

    /** [host] did not answer: it is left alone for a while, and is no longer the kept one. */
    @Synchronized
    fun failed(host: String) {
        if (host !in hosts) return
        cooling[host] = clock() + cooldownMs
        if (current == host) current = null
    }

    /** How many servers are known. */
    @Synchronized
    fun size(): Int = hosts.size

    private fun shuffled(names: List<String>): List<String> = names.shuffled(random)

    companion object {
        /** How long a server that failed is left alone. */
        const val COOLDOWN_MS = 5 * 60_000L

        /** The directory's own domain: every name it publishes ends with it. */
        const val DOMAIN = ".api.radio-browser.info"

        /**
         * What is tried when no name could be discovered: the directory's round-robin name, which resolves to a server, and a few of the
         * servers by their usual names. Last resort only; a name that is gone costs one failed request and the next is tried.
         */
        val FALLBACK: List<String> = listOf(
            "all.api.radio-browser.info",
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "fr1.api.radio-browser.info",
            "nl1.api.radio-browser.info",
            "at1.api.radio-browser.info",
        )

        /** Whether [name] is a host of the directory: its domain, with one plain label in front of it. */
        fun accepts(name: String): Boolean {
            val host = name.trim().lowercase()
            if (!host.endsWith(DOMAIN)) return false
            val label = host.removeSuffix(DOMAIN)
            return label.isNotEmpty() && label.length <= 63 &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                !label.startsWith('-') && !label.endsWith('-')
        }
    }
}
