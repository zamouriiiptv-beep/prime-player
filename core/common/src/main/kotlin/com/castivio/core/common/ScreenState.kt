package com.castivio.core.common

/**
 * What a screen can be showing, as a type rather than as a convention.
 *
 * This exists to hold design invariant 10 — *every screen defines loading, empty,
 * error and success before it is implemented* — with the compiler instead of with
 * review. A screen renders a `ScreenState`, the `when` over it is exhaustive, and a
 * forgotten empty state is a build failure rather than something a user discovers on
 * a television with an empty grid and no way out.
 *
 * It is plain Kotlin and knows nothing about Compose, so the same four states drive
 * a SwiftUI screen or a React one later. The presentation layer differs per
 * platform; what a screen *is* does not.
 *
 * Two things are deliberately not modelled here:
 *
 * - **Paging.** A list that pages does not become `Loading` again when page three
 *   arrives; it stays `Content` and the pager reports its own append state. Making
 *   the whole screen loading on every page is the classic paging bug, and a type
 *   that allowed it would invite it.
 * - **Refresh.** A screen refreshing with content on it is `Content(refreshing =
 *   true)`, never `Loading`, because a spinner over a populated list is a lie about
 *   what is happening. That rule is `UI_ARCHITECTURE.md` §10 and it is enforced here
 *   by there being nowhere else to put it.
 */
sealed interface ScreenState<out T> {

    /**
     * First load, nothing to show yet.
     *
     * Renders as skeletons shaped like the content that is coming — and those
     * skeletons are focusable, so the user's first keypress is never dropped.
     */
    data object Loading : ScreenState<Nothing>

    /**
     * The query succeeded and returned nothing.
     *
     * Separate from [Content] with an empty list on purpose: an empty result needs a
     * reason and an action ("this provider has no movies", "browse Live TV"), and a
     * list-shaped state would render an empty grid instead.
     */
    data class Empty(
        val reason: EmptyReason,
        /** Named in the message when it is the provider's gap rather than the user's. */
        val providerLabel: String? = null,
    ) : ScreenState<Nothing>

    /**
     * The screen could not be loaded, and there is one action that helps.
     *
     * [retryable] is what decides whether that action is "try again" or something
     * else entirely — offering retry on expired credentials wastes the one move the
     * user has.
     */
    data class Failed(
        val error: AppError,
        val retryable: Boolean = true,
    ) : ScreenState<Nothing>

    /**
     * There is content.
     *
     * @param refreshing a background refresh is running *behind* real content. The
     *   screen shows a quiet indicator, never a spinner over the list.
     */
    data class Content<out T>(
        val value: T,
        val refreshing: Boolean = false,
    ) : ScreenState<T>
}

/**
 * Why a screen is empty, which decides what it says and what it offers.
 *
 * An enum rather than a string because the copy is localised and the *action* is
 * derived from the reason — see `UI_ARCHITECTURE.md` §10, where each reason maps to
 * one primary action and at most one secondary.
 */
enum class EmptyReason {
    /** No provider has been added yet. The empty state is the activation call to action. */
    NO_PROVIDER,

    /** The provider was imported and carries nothing of this kind. Never hide the section. */
    PROVIDER_HAS_NO_CONTENT,

    /** This category exists and is empty — the provider's doing, and worth saying so. */
    CATEGORY_EMPTY,

    /** A search returned nothing. The query is echoed back. */
    NO_SEARCH_RESULTS,

    /** Nothing has been favourited yet. */
    NO_FAVORITES,

    /** Nothing watched yet, so nothing to continue or to list as history. */
    NO_HISTORY,
}

/** The value when there is one, or null — for the callers that genuinely only want that. */
fun <T> ScreenState<T>.valueOrNull(): T? = (this as? ScreenState.Content)?.value

/**
 * True while the screen is doing work the user should see an indicator for.
 *
 * Deliberately true for a refresh as well as a first load, because the *indicator*
 * is the same question in both cases even though the treatment is not.
 */
val ScreenState<*>.isBusy: Boolean
    get() = this is ScreenState.Loading || (this is ScreenState.Content && refreshing)

/**
 * Maps the content and leaves every other state alone.
 *
 * The reason this is worth having: without it, a state holder that formats its data
 * ends up re-implementing the four-way `when` in every screen, and one of those
 * copies eventually forgets a case.
 */
inline fun <T, R> ScreenState<T>.map(transform: (T) -> R): ScreenState<R> = when (this) {
    is ScreenState.Content -> ScreenState.Content(transform(value), refreshing)
    is ScreenState.Loading -> this
    is ScreenState.Empty -> this
    is ScreenState.Failed -> this
}
