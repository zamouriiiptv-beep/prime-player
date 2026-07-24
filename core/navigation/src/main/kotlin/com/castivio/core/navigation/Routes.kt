package com.castivio.core.navigation

/**
 * Route contracts. Features expose the routes they own; `:app` wires them
 * together. No feature module ever imports another feature module.
 */
object Routes {
    const val ACTIVATION = "activation"
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"

    fun player(mediaId: String) = "player/$mediaId"
    const val PLAYER_PATTERN = "player/{mediaId}"
    const val ARG_MEDIA_ID = "mediaId"
}
