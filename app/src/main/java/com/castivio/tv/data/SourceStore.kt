package com.castivio.tv.data

import android.content.Context

sealed class PlaylistSource {
    data class Xtream(val server: String, val username: String, val password: String) : PlaylistSource()
    data class M3u(val url: String) : PlaylistSource()
}

/** Stores the playlist source the user configured. */
object SourceStore {
    private const val PREFS = "playlist_source"
    private const val KEY_TYPE = "type"
    private const val KEY_SERVER = "server"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"
    private const val KEY_URL = "url"

    fun save(context: Context, source: PlaylistSource) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        when (source) {
            is PlaylistSource.Xtream -> editor
                .putString(KEY_TYPE, "xtream")
                .putString(KEY_SERVER, source.server)
                .putString(KEY_USER, source.username)
                .putString(KEY_PASS, source.password)
            is PlaylistSource.M3u -> editor
                .putString(KEY_TYPE, "m3u")
                .putString(KEY_URL, source.url)
        }
        editor.apply()
    }

    fun load(context: Context): PlaylistSource? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_TYPE, null)) {
            "xtream" -> PlaylistSource.Xtream(
                prefs.getString(KEY_SERVER, "") ?: "",
                prefs.getString(KEY_USER, "") ?: "",
                prefs.getString(KEY_PASS, "") ?: "",
            )
            "m3u" -> PlaylistSource.M3u(prefs.getString(KEY_URL, "") ?: "")
            else -> null
        }
    }
}
