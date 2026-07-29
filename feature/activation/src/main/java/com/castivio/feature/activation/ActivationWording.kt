package com.castivio.feature.activation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import com.castivio.domain.activation.ActivationFailure
import com.castivio.domain.provider.FieldProblem
import java.text.NumberFormat
import java.util.Locale

/**
 * Where domain values become sentences.
 *
 * One file, because the alternative is a `when` over [ActivationFailure] in every screen
 * that can fail, and the day a case is added only one of them gets the new branch. Both
 * `when`s here are exhaustive, so a new failure or a new field problem does not compile
 * until somebody has written what it says.
 *
 * Nothing here composes a sentence out of fragments. Arabic and English put their words
 * in different orders, and a message assembled from pieces reads correctly in exactly one
 * language — so each case is a whole string with its own resource.
 */
internal data class Wording(val title: String, val detail: String)

@Composable
@ReadOnlyComposable
internal fun ActivationFailure.wording(): Wording = when (this) {
    ActivationFailure.UNREACHABLE -> Wording(
        stringResource(R.string.failure_unreachable_title),
        stringResource(R.string.failure_unreachable_detail),
    )

    ActivationFailure.TIMED_OUT -> Wording(
        stringResource(R.string.failure_timed_out_title),
        stringResource(R.string.failure_timed_out_detail),
    )

    ActivationFailure.REJECTED -> Wording(
        stringResource(R.string.failure_rejected_title),
        stringResource(R.string.failure_rejected_detail),
    )

    ActivationFailure.SUBSCRIPTION_ENDED -> Wording(
        stringResource(R.string.failure_subscription_ended_title),
        stringResource(R.string.failure_subscription_ended_detail),
    )

    ActivationFailure.PROVIDER_REFUSED -> Wording(
        stringResource(R.string.failure_provider_refused_title),
        stringResource(R.string.failure_provider_refused_detail),
    )

    ActivationFailure.NOT_FOUND -> Wording(
        stringResource(R.string.failure_not_found_title),
        stringResource(R.string.failure_not_found_detail),
    )

    ActivationFailure.UNREADABLE -> Wording(
        stringResource(R.string.failure_unreadable_title),
        stringResource(R.string.failure_unreadable_detail),
    )

    ActivationFailure.PROVIDER_ERROR -> Wording(
        stringResource(R.string.failure_provider_error_title),
        stringResource(R.string.failure_provider_error_detail),
    )

    ActivationFailure.EMPTY -> Wording(
        stringResource(R.string.failure_empty_title),
        stringResource(R.string.failure_empty_detail),
    )

    ActivationFailure.UNSUPPORTED -> Wording(
        stringResource(R.string.failure_unsupported_title),
        stringResource(R.string.failure_unsupported_detail),
    )

    ActivationFailure.UNKNOWN -> Wording(
        stringResource(R.string.failure_unknown_title),
        stringResource(R.string.failure_unknown_detail),
    )
}

/** What is wrong with one field, or null when nothing is. */
@Composable
@ReadOnlyComposable
internal fun FieldProblem?.message(): String? = when (this) {
    null -> null
    FieldProblem.REQUIRED -> stringResource(R.string.problem_required)
    FieldProblem.TOO_LONG -> stringResource(R.string.problem_too_long)
    FieldProblem.CONTAINS_SPACES -> stringResource(R.string.problem_contains_spaces)
    FieldProblem.UNSUPPORTED_SCHEME -> stringResource(R.string.problem_unsupported_scheme)
    FieldProblem.INCOMPLETE_HOST -> stringResource(R.string.problem_incomplete_host)
    FieldProblem.INVALID_PORT -> stringResource(R.string.problem_invalid_port)
}

/**
 * A count, grouped the way the reader's language groups digits.
 *
 * `21,874` in English and `٢١٬٨٧٤` in Arabic — the number a user is watching climb during
 * an import is the one piece of text on that screen, and a bare `toString()` gets it
 * wrong in half the locales Castivio ships to.
 */
@Composable
@ReadOnlyComposable
internal fun formatCount(value: Int): String {
    // ConfigurationCompat rather than `configuration.locales`, which is API 24 and
    // Castivio's floor is 21 -- old television boxes are exactly the devices that would
    // have crashed on it, and exactly the ones nobody tests on.
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    return NumberFormat.getIntegerInstance(locale).format(value)
}
