package com.castivio.tv.probe.core

/** How an arm asks the panel for the films. */
enum class Fetch {
    /** `get_vod_streams` once, with no category: the whole section in one reply. */
    ONE_REPLY,

    /** `get_vod_streams&category_id=` for each listed category, over [ProbeArm.workers] connections. */
    BY_CATEGORY,
}

/** What an arm is for in the comparison. */
enum class ArmRole {
    /** The current path, in network terms: every candidate is compared against it. */
    BASELINE,

    /** A way of loading that may replace the baseline. */
    CANDIDATE,

    /** Explains the others and takes no part in the decision. */
    CONTROL,
}

/**
 * One of the ways the Movies section can be fetched, as the probe measures it.
 *
 * [workers] is how many requests are in flight at once. For [Fetch.ONE_REPLY] it is 1: the reply is a
 * single request, and the categories list that travels beside it is not counted as a worker.
 */
data class ProbeArm(
    val id: String,
    val fetch: Fetch,
    val workers: Int,
    val role: ArmRole,
) {
    override fun toString(): String = id
}

/**
 * The five arms the owner approved.
 *
 * **K1** is the current path in network terms -- one reply for the whole section, asked beside the categories
 * as the importer asks it. **K3, K4 and K8** ask for each category on its own, over that many connections, taking
 * the categories in the provider's order from one queue, as the importer's own walk does. **W1** is that walk with
 * one worker: it is not a candidate, it separates what asking category by category costs from what asking over
 * several connections gains.
 */
object ProbeArms {
    val K1 = ProbeArm("K1", Fetch.ONE_REPLY, 1, ArmRole.BASELINE)
    val K3 = ProbeArm("K3", Fetch.BY_CATEGORY, 3, ArmRole.CANDIDATE)
    val K4 = ProbeArm("K4", Fetch.BY_CATEGORY, 4, ArmRole.CANDIDATE)
    val K8 = ProbeArm("K8", Fetch.BY_CATEGORY, 8, ArmRole.CANDIDATE)
    val W1 = ProbeArm("W1", Fetch.BY_CATEGORY, 1, ArmRole.CONTROL)

    /** The arms whose speed is weighed against [K1], narrowest first. */
    val candidates: List<ProbeArm> = listOf(K3, K4, K8)

    val all: List<ProbeArm> = listOf(K1, K3, K4, K8, W1)

    fun byId(id: String): ProbeArm? = all.firstOrNull { it.id == id }
}
