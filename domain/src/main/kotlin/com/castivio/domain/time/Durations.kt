package com.castivio.domain.time

const val MINUTE_MS: Long = 60 * 1000
const val HOUR_MS: Long = 60 * MINUTE_MS
const val DAY_MS: Long = 24 * HOUR_MS

/**
 * Whole days left, rounded **up**.
 *
 * Up, because a subscription with thirty hours left has two days on it in the only
 * sense the user cares about: it will still be working tomorrow. Rounding down shows
 * "1 day" and then keeps working, which reads as a bug.
 *
 * Lives here rather than beside either of its callers because the app licence and a
 * provider subscription both count down, and two roundings of one number is how a
 * product ends up saying "6 days" on one screen and "7 days" on the next.
 */
fun daysRemaining(nowMs: Long, untilMs: Long): Int {
    val remaining = untilMs - nowMs
    if (remaining <= 0) return 0
    return ((remaining + DAY_MS - 1) / DAY_MS).toInt()
}
