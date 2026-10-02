package com.castivio.feature.activation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.LocalDeviceClass
import com.castivio.domain.activation.ActivationForm
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The portal form asks for an address, and asks for nothing else.
 *
 * The claim worth gating is the absence. A portal handshake carries a device MAC, and
 * the obvious way to supply one is a third field — which would be asking the user to
 * copy a number out of the activation screen of this same application, after they have
 * already given it to their provider. The address Castivio sends comes from the device
 * identity, so the form has two fields and the second is the only one that matters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class PortalFormTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `it asks for a name and an address, and for nothing else`() {
        compose.show(ActivationForm.Portal())

        compose.onNodeWithTag(ActivationTags.PORTAL_NAME, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(ActivationTags.PORTAL_URL, useUnmergedTree = true).assertIsDisplayed()

        // Two fields on the form, and that number is the assertion: a third would be
        // the MAC somebody added back.
        compose.onAllNodesWithTag(ActivationTags.PORTAL_NAME, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(ActivationTags.PORTAL_URL, useUnmergedTree = true).assertCountEquals(1)
    }

    /**
     * No MAC anywhere on it, by any of the words it could be called.
     *
     * Stated against the rendered text rather than against the fields, because the way
     * this comes back is somebody adding a label or a hint that asks for one rather
     * than a whole field.
     */
    @Test
    fun `the word MAC does not appear on the portal form`() {
        compose.show(ActivationForm.Portal())

        compose.onNodeWithText("MAC", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    /** Connect is offered only once there is something to connect to. */
    @Test
    fun `connect does nothing until an address has been entered`() {
        var submits = 0
        compose.show(ActivationForm.Portal(), canSubmit = false, onSubmit = { submits++ })

        compose.onNodeWithText("Connect").performClick()

        assertEquals("an empty form was submitted", 0, submits)
    }

    /** And it submits once there is. */
    @Test
    fun `connect submits the address that was entered`() {
        var submits = 0
        compose.show(
            form = ActivationForm.Portal(url = "http://portal.example:8080/c"),
            canSubmit = true,
            onSubmit = { submits++ },
        )

        compose.onNodeWithText("Connect").performClick()

        assertEquals(1, submits)
    }

    /* -------------------------------------------------------------------------- */

    private fun ComposeContentTestRule.show(
        form: ActivationForm.Portal,
        canSubmit: Boolean = true,
        onSubmit: () -> Unit = {},
    ) = setContent {
        CastivioTheme {
            CompositionLocalProvider(LocalDeviceClass provides DeviceClass.Expanded) {
                PortalFormScreen(
                    form = form,
                    enabled = true,
                    canSubmit = canSubmit,
                    onName = {},
                    onUrl = {},
                    onSubmit = onSubmit,
                )
            }
        }
    }
}
