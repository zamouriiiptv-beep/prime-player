package com.castivio.core.navigation

/**
 * Where the app can be.
 *
 * A sealed type rather than the strings this replaces, because a route's arguments
 * change more often than anyone expects and a typo in a string is a crash on a
 * television — the one place a user cannot open a console to find out why. With
 * this, the same mistake is a compile error.
 *
 * Features own their routes and `:app` wires them together; no feature module
 * imports another. The type lives here rather than in `:core:design` on purpose:
 * navigation is a contract, not a widget, and nothing about it should need Compose
 * to be understood or tested.
 */
sealed interface Route {

    /** Stable identity, used as the back-stack and memory key. */
    val key: String

    data object Splash : Route {
        override val key: String get() = "splash"
    }

    /**
     * The app's own licence, which is not the provider's subscription.
     *
     * It sits beside [Activation] rather than inside it because the two gates are
     * independent: this one decides whether Castivio may be used at all, and it is
     * answered before a provider is ever consulted. [reason] chooses the wording; it
     * never changes where the screen sits.
     */
    data class Licence(val reason: LicenceDenial) : Route {
        override val key: String get() = "licence"
    }

    data class Activation(val method: ActivationMethod? = null) : Route {
        override val key: String get() = "activation/${method?.name ?: "choose"}"
    }

    data object Home : Route {
        override val key: String get() = "home"
    }

    /** Live, Movies, Series or Radio. */
    data class Section(val kind: SectionKind) : Route {
        override val key: String get() = "section/${kind.name}"
    }

    data class Category(val kind: SectionKind, val groupId: String) : Route {
        override val key: String get() = "section/${kind.name}/group/$groupId"
    }

    data class Detail(val mediaId: String) : Route {
        override val key: String get() = "detail/$mediaId"
    }

    data class Series(val seriesId: String) : Route {
        override val key: String get() = "series/$seriesId"
    }

    /**
     * @param startPositionMs resume point; null starts from the beginning.
     * @param timeshiftMs offset into a catch-up window. Only ever non-null when the
     *   provider actually exposes an archive — the player renders no rewind
     *   affordance otherwise.
     */
    data class Player(
        val mediaId: String,
        val startPositionMs: Long? = null,
        val timeshiftMs: Long? = null,
    ) : Route {
        // Position is not part of the key: resuming the same item is the same
        // destination, and a back stack that treats them as different would let a
        // user walk back through their own seeks.
        override val key: String get() = "player/$mediaId"
    }

    data class Search(val initialQuery: String? = null) : Route {
        override val key: String get() = "search"
    }

    data class Guide(val channelId: String? = null) : Route {
        override val key: String get() = "guide"
    }

    data object Favorites : Route {
        override val key: String get() = "favorites"
    }

    data object ContinueWatching : Route {
        override val key: String get() = "continue"
    }

    data object History : Route {
        override val key: String get() = "history"
    }

    data class Settings(val section: SettingsSection? = null) : Route {
        override val key: String get() = "settings/${section?.name ?: "root"}"
    }
}

/**
 * The four content sections, named here rather than reused from `:domain`.
 *
 * A destination is not a media kind. Radio and Live are one kind of row in the
 * database and two entries in the rail; the mapping between them belongs to the
 * feature that reads the catalogue, not to the navigation contract.
 */
enum class SectionKind { LIVE, MOVIES, SERIES, RADIO }

enum class ActivationMethod { CODE, XTREAM, PLAYLIST_URL, LOCAL_FILE }

/**
 * Why the licence screen is showing, named here rather than reused from `:domain`.
 *
 * Same reasoning as [SectionKind]: a destination is not a business state. Navigation
 * needs four wordings, not the entitlement model, and keeping the dependency out is
 * what lets this module stay a plain description of where the app can be.
 */
enum class LicenceDenial {
    NOT_ESTABLISHED,
    TRIAL_EXPIRED,
    SUBSCRIPTION_EXPIRED,
    REVOKED,
    VERIFICATION_REQUIRED,
    SERVICE_UNAVAILABLE,
}

enum class SettingsSection {
    PLAYBACK,
    LANGUAGE,
    APPEARANCE,
    PROVIDERS,
    GUIDE,
    PARENTAL,
    DIAGNOSTICS,
    ABOUT,
}

/**
 * Top-level destinations, in rail order.
 *
 * Radio is included but a rail is expected to hide it when the provider has none:
 * an entry that leads to an empty screen is worse than one fewer entry.
 */
val TOP_LEVEL_ROUTES: List<Route> = listOf(
    Route.Home,
    Route.Section(SectionKind.LIVE),
    Route.Section(SectionKind.MOVIES),
    Route.Section(SectionKind.SERIES),
    Route.Section(SectionKind.RADIO),
    Route.Favorites,
    Route.Search(),
    Route.Settings(),
)
