package com.castivio.data.parsing

import com.castivio.domain.PlaylistSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What makes two subscriptions the same subscription.
 *
 * Every catalogue row, every favourite and every watch position is keyed on the id
 * this produces, so it has to answer three questions at once and it had been getting
 * the third wrong:
 *
 *  - the same subscription entered twice is one subscription, including after a
 *    reinstall, or a restored backup points at nothing;
 *  - a rotated password is the same subscription, or changing it costs the user their
 *    history;
 *  - **two different subscriptions are two subscriptions** — which is the one that
 *    failed, and failed invisibly: the row is written with
 *    `OnConflictStrategy.REPLACE`, so the second did not appear beside the first, it
 *    overwrote it.
 */
class SourceIdsTest {

    private fun m3u(url: String) = SourceIds.of(PlaylistSource.M3u(url))

    private fun xtream(host: String, user: String, password: String = "secret") =
        SourceIds.of(PlaylistSource.Xtream(host = host, username = user, password = password))

    /* ------------------------------------------------- the defect this test exists for */

    /**
     * Two accounts on one panel, which is the ordinary case for anybody who buys a
     * second line, and the shape every panel hands out: one host, one `get.php`, the
     * account in the query.
     *
     * The old rule dropped the query to be rid of the password, which left
     * `host/get.php` for both and hashed them to one id.
     */
    @Test
    fun `two subscriptions on one panel are two subscriptions`() {
        val first = m3u("https://panel.example:80/get.php?username=userone&password=aaa&type=m3u_plus")
        val second = m3u("https://panel.example:80/get.php?username=usertwo&password=bbb&type=m3u_plus")

        assertNotEquals("two accounts on one panel hashed to one id", first, second)
    }

    /** And the same for the Xtream path, which already had this right. */
    @Test
    fun `two accounts on one Xtream panel are two subscriptions`() {
        assertNotEquals(xtream("http://panel.example", "userone"), xtream("http://panel.example", "usertwo"))
    }

    /* ----------------------------------------------- what must not change an identity */

    /**
     * A rotated password is the same subscription.
     *
     * This is the property the old rule was reaching for when it threw the query
     * away, and it survives reading the query instead: the secret is named and
     * excluded rather than everything around it being discarded.
     */
    @Test
    fun `a changed password is the same subscription`() {
        val before = m3u("https://panel.example/get.php?username=sam&password=old&type=m3u_plus")
        val after = m3u("https://panel.example/get.php?username=sam&password=new&type=m3u_plus")

        assertEquals(before, after)
    }

    /** The output format is a request, not an identity. */
    @Test
    fun `the same account asked for a different format is the same subscription`() {
        val plus = m3u("https://panel.example/get.php?username=sam&password=x&type=m3u_plus")
        val plain = m3u("https://panel.example/get.php?username=sam&password=x&type=m3u")

        assertEquals(plus, plain)
    }

    /**
     * And neither is the path on the panel, nor the case the user typed it in.
     *
     * A panel is one account whether it was pasted as `get.php` or as
     * `player_api.php`, because what was pasted is the same account either way.
     */
    @Test
    fun `the same account is one subscription however the panel URL was pasted`() {
        val get = m3u("https://Panel.example/get.php?username=Sam&password=x")
        val api = m3u("https://panel.example/player_api.php?username=sam&password=y")

        assertEquals(get, api)
    }

    /** A panel that reorders its own query has not handed out a second playlist. */
    @Test
    fun `query order does not change a static playlist's identity`() {
        assertEquals(
            m3u("https://files.example/list.php?id=7&fmt=ts"),
            m3u("https://files.example/list.php?fmt=ts&id=7"),
        )
    }

    /* ------------------------------------------- the shape with no account in it */

    /**
     * A playlist that names no user is a file, and a file is told from another file
     * by its address — including its query.
     *
     * The old rule discarded the query here too, so these two were one playlist.
     */
    @Test
    fun `two static playlists on one host are two playlists`() {
        assertNotEquals(
            m3u("https://files.example/list.php?id=1"),
            m3u("https://files.example/list.php?id=2"),
        )
    }

    /** A plain address with no query at all is keyed as it always was. */
    @Test
    fun `a plain playlist address is still the address`() {
        assertEquals(
            m3u("https://files.example/playlist.m3u"),
            m3u("HTTPS://files.example/playlist.m3u/"),
        )
    }

    /* ------------------------------------------------------ the kinds stay apart */

    /**
     * The same account added both ways is two rows, and should be.
     *
     * They are the same credentials and not the same thing: an Xtream source has an
     * API behind it and a playlist does not, so they import differently and are
     * capable of different things. `kind` is inside the hash, which is what keeps
     * them apart without either path having to know about the other.
     */
    @Test
    fun `the same account as a playlist and as Xtream are two sources`() {
        assertNotEquals(
            m3u("https://panel.example/get.php?username=sam&password=x"),
            xtream("https://panel.example", "sam"),
        )
    }
}
