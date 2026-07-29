package com.castivio.data.entitlement

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.entitlement.PricingConfig
import com.castivio.domain.entitlement.TrialGrant
import com.castivio.domain.entitlement.TrialGrantor
import com.castivio.domain.identity.DeviceIdentityRecord

/**
 * The development trial, and nothing else.
 *
 * **This is not a licence authority.** It implements [TrialGrantor] and only
 * [TrialGrantor], which means the strongest statement it can make is "a trial started
 * here and ends then". It cannot express an annual subscription, a lifetime purchase, a
 * revocation or a recovery, because [TrialGrant] has nowhere to put them — those are
 * facts about money that changed hands, and a device cannot know them, it can only be
 * told by [com.castivio.domain.entitlement.EntitlementSource].
 *
 * Two things it cannot do that the production trial must:
 *
 *  - **Remember.** Clearing the app's data removes the record, and this grants another
 *    week to the same device without hesitation, because it has no memory of the first.
 *    Only a server that keeps the address can refuse a second trial, which is why the
 *    real trial is server-granted.
 *  - **Refuse.** It has no view of abuse, of refunds, or of a device that has been seen
 *    a thousand times.
 *
 * So it is off in a release build. A shipped APK with no licence server has nothing
 * that can honestly hand out a free week, and one that did would be a licensing system
 * with no licences in it — every install entitled, forever, by its own say-so.
 *
 * @param enabled `BuildConfig.LOCAL_TRIAL`: true in debug, false in release.
 */
internal class LocalEntitlementSource(
    private val config: PricingConfig,
    private val enabled: Boolean,
) : TrialGrantor {

    override suspend fun grant(
        identity: DeviceIdentityRecord,
        nowMs: Long,
    ): Outcome<TrialGrant> {
        if (!enabled) return Outcome.Failure(AppError.NOT_CONFIGURED)

        // The duration comes from the configuration, never from a constant here: seven
        // days is a business decision and belongs where the prices are.
        return Outcome.Success(
            TrialGrant(
                startedAtMs = nowMs,
                expiresAtMs = nowMs + config.trialDurationMs,
            ),
        )
    }
}
