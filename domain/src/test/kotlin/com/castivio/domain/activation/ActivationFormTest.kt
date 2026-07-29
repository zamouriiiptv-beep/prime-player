package com.castivio.domain.activation

import com.castivio.domain.PlaylistSource
import com.castivio.domain.provider.FieldProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the screen shows while the user is still typing.
 *
 * Everything here is derived from the text, with nothing cached — which is the point.
 * A remembered "is valid" flag is how a Continue button ends up enabled over an empty
 * password, and it is the kind of bug that only shows up on the one device you did not
 * test on.
 */
class ActivationFormTest {

    // ------------------------------------------------------------------- xtream

    @Test
    fun `an empty xtream form cannot be submitted`() {
        val form = ActivationForm.Xtream()

        assertFalse(form.canSubmit)
        assertNull(form.source)
    }

    @Test
    fun `a complete xtream form submits the normalised values`() {
        val form = ActivationForm.Xtream(
            name = "  Home  ",
            serverUrl = "line.example.com:8080/player_api.php",
            username = " bob ",
            password = "hunter2\n",
        )

        assertTrue(form.canSubmit)
        assertEquals("Home", form.label)
        assertEquals(
            PlaylistSource.Xtream("http://line.example.com:8080", "bob", "hunter2"),
            form.source,
        )
    }

    @Test
    fun `the name stays optional`() {
        val form = ActivationForm.Xtream(
            serverUrl = "line.example.com:8080",
            username = "bob",
            password = "hunter2",
        )

        assertTrue(form.canSubmit)
        assertNull(form.label)
    }

    @Test
    fun `one bad field withholds the whole form and marks only itself`() {
        val form = ActivationForm.Xtream(
            serverUrl = "rtmp://line.example.com",
            username = "bob",
            password = "hunter2",
        )

        assertFalse(form.canSubmit)
        assertNull(form.source)
        assertEquals(FieldProblem.UNSUPPORTED_SCHEME, form.checked.serverUrl.problem)
        assertTrue(form.checked.username.isValid)
        assertTrue(form.checked.password.isValid)
    }

    /** Validity follows the text on every keystroke rather than trailing behind it. */
    @Test
    fun `validity is derived, never remembered`() {
        var form = ActivationForm.Xtream(serverUrl = "line.example.com", username = "bob", password = "hunter2")
        assertTrue(form.canSubmit)

        form = form.copy(password = "")
        assertFalse(form.canSubmit)

        form = form.copy(password = "hunter2")
        assertTrue(form.canSubmit)
    }

    // ----------------------------------------------------------------- playlist

    @Test
    fun `a complete playlist form submits`() {
        val form = ActivationForm.Playlist(name = "Backup", url = "line.example.com/playlist.m3u8")

        assertTrue(form.canSubmit)
        assertEquals("Backup", form.label)
        assertEquals(PlaylistSource.M3u("http://line.example.com/playlist.m3u8"), form.source)
        assertNull(form.detectedXtream)
    }

    @Test
    fun `an empty playlist form cannot be submitted`() {
        assertFalse(ActivationForm.Playlist().canSubmit)
    }

    /**
     * The link providers actually e-mail. It is a perfectly good playlist and stays
     * submittable as one — the detection is an offer the screen makes, never a rewrite
     * of what somebody typed.
     */
    @Test
    fun `an xtream link pasted as a playlist is offered, not applied`() {
        val form = ActivationForm.Playlist(
            url = "http://line.example.com:8080/get.php?username=bob&password=hunter2&type=m3u_plus",
        )

        assertTrue(form.canSubmit)
        assertEquals(
            PlaylistSource.M3u("http://line.example.com:8080/get.php?username=bob&password=hunter2&type=m3u_plus"),
            form.source,
        )
        assertEquals(
            PlaylistSource.Xtream("http://line.example.com:8080", "bob", "hunter2"),
            form.detectedXtream,
        )
    }

    @Test
    fun `accepting the offer fills the xtream form`() {
        val playlist = ActivationForm.Playlist(
            name = "Home",
            url = "http://line.example.com:8080/get.php?username=bob&password=hunter2",
        )

        val xtream = playlist.detectedXtream!!.asForm(name = playlist.name)

        assertEquals(
            ActivationForm.Xtream(
                name = "Home",
                serverUrl = "http://line.example.com:8080",
                username = "bob",
                password = "hunter2",
            ),
            xtream,
        )
        assertTrue(xtream.canSubmit)
        assertEquals(playlist.detectedXtream, xtream.source)
    }

    // ------------------------------------------------------------------- the state

    @Test
    fun `an idle state with a complete form can be submitted`() {
        val state = ActivationUiState(
            form = ActivationForm.Xtream(serverUrl = "line.example.com", username = "bob", password = "x"),
        )

        assertTrue(state.canSubmit)
        assertFalse(state.busy)
        assertFalse(state.canCancel)
    }

    /** Nothing is editable and nothing is resubmittable while an attempt is running. */
    @Test
    fun `a running attempt cannot be submitted again`() {
        val complete = ActivationForm.Xtream(serverUrl = "line.example.com", username = "bob", password = "x")

        for (phase in listOf(ActivationPhase.Checking, ActivationPhase.Importing(1_000, 2))) {
            val state = ActivationUiState(complete, phase)

            assertTrue("$phase", state.busy)
            assertFalse("$phase", state.canSubmit)
            assertTrue("$phase", state.canCancel)
        }
    }

    @Test
    fun `a finished attempt is neither busy nor cancellable`() {
        val complete = ActivationForm.Xtream(serverUrl = "line.example.com", username = "bob", password = "x")
        val finished = listOf(
            ActivationPhase.Editing,
            ActivationPhase.Failed(ActivationFailure.UNREACHABLE),
        )

        for (phase in finished) {
            val state = ActivationUiState(complete, phase)

            assertFalse("$phase", state.busy)
            assertFalse("$phase", state.canCancel)
            assertTrue("$phase", state.canSubmit)
        }
    }
}
