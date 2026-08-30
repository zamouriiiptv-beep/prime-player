package com.castivio.feature.player

import com.castivio.playback.api.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the share button is allowed to hand to another application.
 *
 * ## The claim that matters is the one about credentials
 *
 * A subscription URL carries the subscriber's username, password or session token in its
 * query. Sharing a film to a messaging application is an ordinary thing to do; sharing the
 * account is not, and the two are one press apart. So the rule lives in a function with no
 * `Intent` and no `Context` in it, and this file holds it: a local file is shared as itself,
 * and anything that arrived over a network is shared by name only.
 *
 * The same rule, in a different place, is why `Media3Engine` strips the query before a URL
 * reaches a diagnostic report — a report the user is invited to copy and paste to somebody.
 */
class PlayerShareTest {

    @Test
    fun `a file on the device is shared as the file`() {
        val offer = shareOffer(request("content://media/external/video/media/7"))

        assertEquals(
            ShareOffer.File("content://media/external/video/media/7", "الطريق إلى شفشاون"),
            offer,
        )
    }

    @Test
    fun `a file URL is local too`() {
        assertTrue(shareOffer(request("file:///sdcard/Movies/a.mp4")) is ShareOffer.File)
    }

    /**
     * The one that would be a security defect if it went the other way.
     *
     * Written as an assertion about the *type* and then about the text, because "it shares
     * the title" is only half of it: what is being claimed is that the URL is nowhere in
     * what leaves the application.
     */
    @Test
    fun `a subscription stream is shared by name and never by address`() {
        val url = "http://provider.tv/live/1.ts?username=sami&password=hunter2&token=abc"
        val offer = shareOffer(request(url))

        assertEquals(ShareOffer.Words("الطريق إلى شفشاون"), offer)
        assertTrue(
            "the URL, or a piece of it, left the application in a share",
            offer is ShareOffer.Words && listOf("provider.tv", "password", "hunter2", "abc")
                .none { it in offer.title },
        )
    }

    /** HTTPS is not a different case, and a rule that treated it as one would be a bug. */
    @Test
    fun `a secure stream is shared by name as well`() {
        assertTrue(shareOffer(request("https://provider.tv/movie/9.mkv")) is ShareOffer.Words)
    }

    /**
     * Case is not part of a scheme.
     *
     * `Uri` treats schemes as case-insensitive and a source list written by hand will
     * eventually contain one in capitals. Getting this wrong fails safe — the file would be
     * shared by name rather than as a file — but it would be wrong.
     */
    @Test
    fun `a scheme in capitals is still the device's own`() {
        assertTrue(shareOffer(request("CONTENT://media/external/video/media/7")) is ShareOffer.File)
    }

    private fun request(url: String) = PlayerRequest(
        url = url,
        title = "الطريق إلى شفشاون",
        kind = MediaKind.VOD,
    )
}
