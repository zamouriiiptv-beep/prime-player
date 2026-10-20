package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One subscription, two questions: how its catalogue is read, and how its account is asked.
 *
 * They part in exactly one case -- a playlist whose rows came from its file but whose link
 * names a panel -- and these tests hold both halves of that case: the rows stay with the
 * file, and the account goes to the panel.
 */
class AccountSourceTest {

    private val link = "http://line.example.com:8080/get.php?username=sam&password=hunter2&type=m3u_plus"

    private fun playlist(contentHash: String?) = ProviderSource(
        id = "playlist-1",
        kind = SourceKind.M3U_URL,
        label = "Playlist 1",
        url = link,
        userAgent = "Agent/1",
        sync = SyncState(contentHash = contentHash),
    )

    /** The case the two functions exist to tell apart. */
    @Test
    fun `a playlist read from its file is read from it, and asked about through its panel`() {
        val source = playlist(contentHash = "read-to-its-end")

        assertNull("its rows stay keyed the way they were read", (source.asPlaylistSource() as PlaylistSource.M3u).xtream)

        val asked = source.asAccountSource() as PlaylistSource.M3u
        assertEquals("sam", asked.xtream?.username)
        assertEquals("hunter2", asked.xtream?.password)
        assertEquals("http://line.example.com:8080", asked.xtream?.host)
        assertEquals("the playlist is still itself", link, asked.url)
        assertEquals("Agent/1", asked.userAgent)
    }

    @Test
    fun `a playlist read through its panel is asked through the same panel`() {
        val source = playlist(contentHash = null)

        assertEquals(source.asPlaylistSource(), source.asAccountSource())
        assertNotNull((source.asAccountSource() as PlaylistSource.M3u).xtream)
    }

    /** A playlist with no account in its link has no panel to ask, read either way. */
    @Test
    fun `a playlist whose link names no panel is asked about as itself`() {
        val source = ProviderSource(id = "plain", kind = SourceKind.M3U_URL, label = "plain", url = "http://cdn.example.com/lists/family.m3u")

        assertNull((source.asAccountSource() as PlaylistSource.M3u).xtream)
        assertEquals(source.asPlaylistSource(), source.asAccountSource())
    }

    @Test
    fun `every other kind is asked about as it is read`() {
        val sources = listOf(
            ProviderSource(id = "x", kind = SourceKind.XTREAM, label = "x", url = "http://panel.test", username = "u", password = "p"),
            ProviderSource(id = "p", kind = SourceKind.PORTAL, label = "p", url = "http://portal.test/c/"),
            ProviderSource(id = "f", kind = SourceKind.LOCAL_FILE, label = "f", url = "content://downloads/list.m3u"),
            ProviderSource(id = "n", kind = SourceKind.XTREAM, label = "n", url = "http://panel.test"),
        )

        for (source in sources) assertEquals(source.kind.name, source.asPlaylistSource(), source.asAccountSource())
    }

    /** No address at all: nothing to read, and nobody to ask. */
    @Test
    fun `a playlist with no link has nobody to ask`() {
        assertNull(ProviderSource(id = "empty", kind = SourceKind.M3U_URL, label = "empty", url = null).asAccountSource())
    }
}
