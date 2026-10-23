package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.castivio.core.design.components.CastivioNoticeDialog
import com.castivio.core.design.components.CastivioPinDialog
import com.castivio.domain.PinRules
import kotlinx.coroutines.launch

/**
 * The password dialog for the channel lock, in every one of its uses.
 *
 * ## One composable, because it is one conversation
 *
 * Pressing a locked channel, locking one, opening one and changing the password are all the same
 * thing from the viewer's side: four digits, typed on a keypad, checked, and then something happens.
 * They differ in the words and in what follows, and both are decided here from [purpose] -- the
 * dialog itself ([CastivioPinDialog]) knows nothing about channels or about what is right.
 *
 * ## What it does when the password is right
 *
 * Calls [onGranted] once and does nothing else. It does not lock, unlock or play: the caller knows
 * what the password was asked for, and a dialog that did the thing itself would have to know every
 * one of them. For [PinPurpose.Change] it is the end of the whole conversation, after the new
 * password has been stored and said to be.
 *
 * ## Back
 *
 * Closes it, as the Cancel button does and a press on the scrim does. Nothing was granted and
 * nothing is changed.
 *
 * ## No state outside the composition
 *
 * What was typed is in the keypad and what has been asked is in a [PinPromptState]; both are gone
 * when the dialog is. A password that was typed and not yet checked is never kept anywhere a screen
 * rotation or a process death could hand it back.
 */
@Composable
fun PinPrompt(
    purpose: PinPurpose,
    onGranted: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    model: ChannelLockViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)

    val scope = rememberCoroutineScope()
    var state by remember(purpose) { mutableStateOf(PinPromptState.start(purpose)) }

    if (state.changed) {
        CastivioNoticeDialog(
            title = stringResource(R.string.pin_changed_title),
            body = stringResource(R.string.pin_changed_body),
            closeLabel = stringResource(R.string.pin_done),
            onClose = onGranted,
            modifier = modifier,
        )
        return
    }

    val (title, message) = when (val step = state.step) {
        PinStep.Check -> when (purpose) {
            PinPurpose.Lock -> R.string.pin_title_lock to R.string.pin_message_lock
            PinPurpose.Unlock -> R.string.pin_title_unlock to R.string.pin_message_unlock
            else -> R.string.pin_title_play to R.string.pin_message_play
        }
        PinStep.Current -> R.string.pin_title_current to R.string.pin_message_current
        is PinStep.New -> R.string.pin_title_new to R.string.pin_message_new
        is PinStep.Repeat -> R.string.pin_title_repeat to R.string.pin_message_repeat
    }

    CastivioPinDialog(
        title = stringResource(title),
        message = stringResource(message),
        cancelLabel = stringResource(R.string.pin_cancel),
        deleteLabel = stringResource(R.string.pin_delete),
        progressLabel = { entered -> stringResource(R.string.pin_progress, entered, PinRules.LENGTH) },
        error = state.notice?.let {
            stringResource(if (it == PinNotice.Wrong) R.string.pin_wrong else R.string.pin_mismatch)
        },
        attempt = state.attempt,
        length = PinRules.LENGTH,
        onComplete = { typed ->
            val asked = state
            scope.launch {
                // Only the steps that compare with the stored password ask the store; the others
                // are about two typings agreeing with each other.
                val right = asked.checksPassword && model.matches(typed)
                when (val turn = asked.entered(typed, right)) {
                    PinTurn.Granted -> onGranted()
                    is PinTurn.Stay -> state = turn.state
                    is PinTurn.Save -> state = asked.saved(model.change(turn.current, turn.new))
                }
            }
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}
