package com.castivio.feature.home

import com.castivio.domain.PinChange

/**
 * What a password is being asked for.
 *
 * Public because the shell asks too: a locked channel pressed from search is asked for in the same
 * dialog as one pressed on the board, and changing the password is asked for from Settings.
 */
enum class PinPurpose {
    /** A locked channel is about to play. */
    Play,

    /** A channel is about to be locked. */
    Lock,

    /** A locked channel is about to be opened for good. */
    Unlock,

    /** The password itself is being changed: the current one, then the new one twice. */
    Change,
}

/** Where in the dialog's questions a viewer is. */
internal sealed interface PinStep {

    /** One password, checked against the stored one: [PinPurpose.Play], [PinPurpose.Lock], [PinPurpose.Unlock]. */
    data object Check : PinStep

    /** The first question of a change: the password as it is now. */
    data object Current : PinStep

    /** The second: the one it is to become. */
    data class New(val current: String) : PinStep

    /** The third: the same digits again, because a password nobody can read back cannot have a typing mistake in it. */
    data class Repeat(val current: String, val candidate: String) : PinStep
}

/** What is wrong with the digits that were just typed. */
internal enum class PinNotice {
    /** Not the password. */
    Wrong,

    /** The second typing of a new password was not the first. */
    Mismatch,
}

/**
 * The dialog's whole state, with no screen in it.
 *
 * [attempt] rises with every refusal, which is what tells the keypad to clear itself when the same
 * sentence is shown twice in a row. [changed] is the end of a change: the password is stored and the
 * dialog says so before it goes.
 */
internal data class PinPromptState(
    val step: PinStep,
    val attempt: Int = 0,
    val notice: PinNotice? = null,
    val changed: Boolean = false,
) {

    /** Whether what is typed at this step has to be checked against the stored password. */
    val checksPassword: Boolean get() = step is PinStep.Check || step is PinStep.Current

    private fun refused(why: PinNotice) = copy(attempt = attempt + 1, notice = why)

    /**
     * What typing [pin] does, given [right] -- whether it is the stored password, which only the
     * steps that check it look at.
     */
    fun entered(pin: String, right: Boolean): PinTurn = when (step) {
        PinStep.Check ->
            if (right) PinTurn.Granted else PinTurn.Stay(refused(PinNotice.Wrong))

        PinStep.Current ->
            if (right) PinTurn.Stay(copy(step = PinStep.New(pin), notice = null))
            else PinTurn.Stay(refused(PinNotice.Wrong))

        is PinStep.New ->
            PinTurn.Stay(copy(step = PinStep.Repeat(step.current, pin), notice = null))

        is PinStep.Repeat ->
            if (pin == step.candidate) PinTurn.Save(current = step.current, new = pin)
            // Not the same twice: both are thrown away, because nobody knows which was the slip.
            else PinTurn.Stay(copy(step = PinStep.New(step.current)).refused(PinNotice.Mismatch))
    }

    /**
     * What the stored password said to the change [entered] asked for.
     *
     * Only [PinChange.Changed] ends it. The other two cannot be reached by typing -- the current
     * password was just checked and the new one is four digits -- so reaching one means the file
     * changed under the dialog, and the honest answer is to begin again from the password as it is.
     */
    fun saved(result: PinChange): PinPromptState = when (result) {
        PinChange.Changed -> copy(changed = true, notice = null)
        PinChange.WrongCurrent, PinChange.Malformed ->
            PinPromptState(step = PinStep.Current, attempt = attempt + 1, notice = PinNotice.Wrong)
    }

    companion object {
        fun start(purpose: PinPurpose) = PinPromptState(
            step = if (purpose == PinPurpose.Change) PinStep.Current else PinStep.Check,
        )
    }
}

/** What happens next, after a password has been typed. */
internal sealed interface PinTurn {

    /** The password was right, and what it was asked for goes ahead. */
    data object Granted : PinTurn

    /** The dialog stays, in this state: a refusal, or the next question. */
    data class Stay(val state: PinPromptState) : PinTurn

    /** Both typings agree: store [new] as the password, replacing [current]. */
    data class Save(val current: String, val new: String) : PinTurn
}
