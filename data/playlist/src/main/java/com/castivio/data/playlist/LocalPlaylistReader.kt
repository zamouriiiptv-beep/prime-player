package com.castivio.data.playlist

import android.content.Context
import android.net.Uri
import com.castivio.data.networking.HashingInputStream
import com.castivio.data.networking.decompressIfNeeded
import java.io.InputStreamReader
import java.io.Reader

/**
 * Opens a playlist the user picked from storage.
 *
 * A content URI, not a path: the document picker on modern Android hands back a
 * URI that no file path can be derived from, and asking for storage permission to
 * read one file is the wrong trade on a TV where typing is painful.
 *
 * The stream is hashed as it is read for the same reason the HTTP path does it: a
 * local file has no ETag, so the hash is the only way to know whether re-importing
 * is worth the work.
 */
interface LocalPlaylistReader {
    /** Null when the file is gone — picked once, moved or deleted since. */
    fun open(uri: String): OpenedPlaylist?
}

class OpenedPlaylist(
    val reader: Reader,
    private val hashing: HashingInputStream,
) {
    /** Only meaningful once [reader] has been consumed to the end. */
    fun fingerprint(): String = hashing.fingerprint()
}

class AndroidLocalPlaylistReader(private val context: Context) : LocalPlaylistReader {

    override fun open(uri: String): OpenedPlaylist? {
        val stream = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))
        }.getOrNull() ?: return null

        // Gzip is sniffed here too: users do pick `playlist.m3u.gz`.
        val hashing = HashingInputStream(decompressIfNeeded(stream))
        return OpenedPlaylist(
            reader = InputStreamReader(hashing, Charsets.UTF_8).buffered(READ_BUFFER),
            hashing = hashing,
        )
    }

    private companion object {
        const val READ_BUFFER = 1 shl 16
    }
}
