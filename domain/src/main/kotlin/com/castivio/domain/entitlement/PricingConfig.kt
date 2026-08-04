package com.castivio.domain.entitlement

/**
 * Everything about what a plan costs and how long it lasts, in one replaceable value.
 *
 * The rule this type exists to enforce: **no duration, price or currency appears
 * anywhere else.** Not in a screen, not in a policy, not in a string. A price is a
 * business decision that changes without a release, and a number copied into a
 * `when` branch is a number that will be wrong in six months and found by a customer.
 *
 * [EntitlementPolicy] takes a config rather than reading a default, so the policy has
 * no opinion about what a trial is worth — it only knows how to compare instants.
 * Today the values come from [PricingDefaults]; tomorrow they come from the licence
 * server, and nothing above this type changes.
 */
data class PricingConfig(
    /** How long a granted trial lasts. */
    val trialDurationMs: Long,

    /**
     * How long a cached entitlement stays usable without the server confirming it.
     *
     * This is the number that decides whether our outage becomes the user's problem,
     * so it is generous by default and configurable by us at any time.
     */
    val offlineGraceMs: Long,

    /** How often the app would like to re-confirm with the server. */
    val verifyIntervalMs: Long,

    val plans: List<PlanOffer>,
) {
    /** The offer for a plan, or null when it is not being sold on this device. */
    fun offer(plan: Plan): PlanOffer? = plans.firstOrNull { it.plan == plan && it.available }

    /** The plans a purchase screen should show, in the order they should appear. */
    val purchasable: List<PlanOffer> get() = plans.filter { it.available && it.plan != Plan.TRIAL }
}

/**
 * One purchasable plan.
 *
 * [priceMinor] is an integer in the currency's minor unit — 600 is €6.00 — because
 * money in a floating-point type is a defect waiting for a rounding error. Formatting
 * it for a locale is the presentation layer's job; the domain only carries the fact.
 */
data class PlanOffer(
    val plan: Plan,
    val priceMinor: Long,
    /** ISO 4217, e.g. "EUR". */
    val currency: String,
    /** How long a purchase lasts. Null for a perpetual plan. */
    val periodMs: Long? = null,
    /** False hides the plan without deleting it — a store or a region may not sell it. */
    val available: Boolean = true,
)

/**
 * The values shipped in the binary, used until the licence server answers.
 *
 * They live here, alone, and are referenced by exactly one place: whoever builds the
 * initial [PricingConfig]. No policy and no screen may read them, which is what keeps
 * "the price is configurable" true rather than aspirational.
 */
object PricingDefaults {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val YEAR_MS = 365L * DAY_MS

    /** 7-day free trial. */
    const val TRIAL_DAYS = 7

    /** Two weeks without reaching the server before a cached entitlement stops counting. */
    const val OFFLINE_GRACE_DAYS = 14

    /** Try to re-confirm daily; failing to is not an error until the grace runs out. */
    const val VERIFY_INTERVAL_HOURS = 24

    /** €6.00 per year. */
    const val ANNUAL_PRICE_MINOR = 600L

    /** €15.00, once. */
    const val LIFETIME_PRICE_MINOR = 1_500L

    /**
     * The euro, for every plan.
     *
     * One currency for the whole model rather than one per offer: a catalogue
     * with mixed currencies in it is a catalogue where "cheaper" stops being a
     * comparison, and nothing in Castivio converts between them.
     */
    const val CURRENCY = "EUR"

    val config: PricingConfig = PricingConfig(
        trialDurationMs = TRIAL_DAYS * DAY_MS,
        offlineGraceMs = OFFLINE_GRACE_DAYS * DAY_MS,
        verifyIntervalMs = VERIFY_INTERVAL_HOURS * 60L * 60 * 1000,
        plans = listOf(
            PlanOffer(
                plan = Plan.TRIAL,
                priceMinor = 0,
                currency = CURRENCY,
                periodMs = TRIAL_DAYS * DAY_MS,
            ),
            PlanOffer(
                plan = Plan.ANNUAL,
                priceMinor = ANNUAL_PRICE_MINOR,
                currency = CURRENCY,
                periodMs = YEAR_MS,
            ),
            PlanOffer(
                plan = Plan.LIFETIME,
                priceMinor = LIFETIME_PRICE_MINOR,
                currency = CURRENCY,
                periodMs = null,
            ),
        ),
    )
}
