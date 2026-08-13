package com.castivio.feature.settings

/**
 * Module boundary placeholder — implementation lands with the feature.
 *
 * ## What already depends on this module existing
 *
 * **The licence screen's entry point.** `Settings → Licence` is a locked product
 * decision and it is reachable today, from the UX-validation shell's Settings
 * screen. When the real Settings arrives it takes that entry over, and the whole
 * change is one call:
 *
 * ```kotlin
 * // wherever the real Settings renders its Licence row
 * LicenceWithLanguage(onLeave = { navigateBackToSettings() })
 * ```
 *
 * `com.castivio.tv.licence.LicenceWithLanguage` is the single entry point. It
 * lives in `:app` because applying a language means wrapping the activity's
 * `Context`, which is the application's business and not a feature's. Nothing in
 * `:feature:licence` changes and nothing about the screen changes — the only
 * thing a caller supplies is what "leave" means, because only the caller knows
 * where the user came from.
 *
 * Recorded here rather than in a tracker, because this is the file somebody
 * opens on the day they start the Settings feature.
 */
internal object SettingsFeature
