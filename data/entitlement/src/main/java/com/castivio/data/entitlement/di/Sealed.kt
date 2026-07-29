package com.castivio.data.entitlement.di

import javax.inject.Qualifier

/**
 * The preferences file that holds the licence, as opposed to the several that hold
 * settings.
 *
 * Qualified rather than named at the point of use so that nothing else in the app can
 * ask for "some SharedPreferences" and be handed this one by accident — the file that
 * survives a settings reset is not the file a settings screen should be writing to.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Sealed
