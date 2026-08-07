package com.castivio.tv.root

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.castivio.core.design.components.CastivioDialog
import com.castivio.tv.R

/**
 * The one question Castivio asks before it closes, asked wherever the user is.
 *
 * ## Why it moved
 *
 * It used to live in `ShellScreen`, which meant it could only be reached from
 * the shell — and the gate does not send a new device to the shell. A fresh
 * install has no playlist, so `StartGate` routes to Add a playlist; a device
 * with no licence goes to the licence screen. Both called `finish()` on Back
 * with nothing in between. The confirmation existed, was tested, and was
 * unreachable on the one launch where a stray Back costs the most.
 *
 * That is not a bug in the dialog. It is a bug in where it was: a rule about
 * *leaving the application* was being enforced by one screen inside it.
 *
 * ## Who owns Back
 *
 * This does, while the question is open, and nothing else does. The handler is
 * registered after [content] composes, so it is the innermost enabled callback
 * and wins over the shell's ladder and over the gate routes' own handlers —
 * every one of which would otherwise answer a Back that was meant for the
 * dialog. `BackPolicy.fromShell` lost its dialog rung to this and is the better
 * for it: the shell decides where Back goes *inside* Castivio, and stops there.
 *
 * ## Why the flag survives a rebuild
 *
 * `rememberSaveable`, so a rotation or a night-mode change does not silently
 * withdraw a question the user is halfway through reading. The dialog is not
 * process-death state — there is nothing to restore into if the process died,
 * because the activity is gone — but a configuration change is not that, and
 * losing the dialog to one is a small rudeness with no upside.
 *
 * @param onExit what the confirm button does. `finish()`, and nothing else:
 *   this composable's whole job is to make sure it is deliberate.
 */
@Composable
internal fun ExitGuard(
    onExit: () -> Unit,
    content: @Composable (askToExit: () -> Unit) -> Unit,
) {
    var asked by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        content { asked = true }

        if (asked) {
            // Registered inside the branch, so it exists only while the question
            // does. An always-enabled handler here would swallow the Back that
            // the shell and the gate are entitled to.
            BackHandler(enabled = true) { asked = false }

            CastivioDialog(
                title = stringResource(R.string.exit_title),
                message = stringResource(R.string.exit_message),
                confirmLabel = stringResource(R.string.exit_confirm),
                dismissLabel = stringResource(R.string.exit_cancel),
                onConfirm = onExit,
                onDismiss = { asked = false },
            )
        }
    }
}
