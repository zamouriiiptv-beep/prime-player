package com.castivio.feature.activation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.castivio.core.design.components.CastivioBackChip
import com.castivio.core.design.components.CastivioHeader
import com.castivio.core.design.components.CastivioHeaderTitle
import com.castivio.core.design.components.CastivioLockup
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.theme.Palette

/**
 * The header every step of this flow wears: mark, name, question, and the way back.
 *
 * ## Why one function and not five
 *
 * Five screens in this module put a title at the top and a way back somewhere near it,
 * and until now each did it differently: the source choice used `CastivioHeader` with a
 * Back chip, the media chooser and the library scaffold drew a title with a corner
 * wordmark and put Back in a footer of their own, and the saved-subscriptions step drew
 * a bare title and a full-width button at the bottom. Three headers, two footers, and
 * one wordmark at three sizes — for one row that says the same thing on all five.
 *
 * A reader moving between them saw the brand change size and Back change place. Nobody
 * can point at that; everybody feels it.
 *
 * ## What the row does about direction
 *
 * The lockup is at the same **physical** edge in every language — the brand is not
 * language, and a signature that reassembles itself per locale is two signatures — so
 * `CastivioHeader` pins the row left to right and lets each element's own text run in
 * its own direction. Back therefore keeps the far end whatever the language, which is
 * where a thumb reaches and where an eye returning to the screen lands.
 *
 * @param m the frame's numbers. [SourceMetrics] rather than a bare frame because these
 *   screens already carry one, and it is the frame with delegating names on it.
 * @param headingTag tagged per screen, so a layout test can say *this* screen's title
 *   is above *this* screen's content rather than that some heading exists.
 */
@Composable
internal fun ChooserHeader(
    m: SourceMetrics,
    title: String,
    headingTag: String,
    backTag: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CastivioHeader(
        height = m.header,
        gap = m.headGap,
        modifier = modifier.fillMaxWidth(),
        lockup = {
            CastivioLockup(
                markSize = m.brand,
                wordSize = (m.fsTitle.value * WORD_RATIO).sp,
                modifier = Modifier.testTag(ActivationTags.HEADER_MARK),
            )
        },
        title = {
            CastivioHeaderTitle(
                text = title,
                style = castivioTitleStyle(m.fsTitle),
                color = Palette.White,
                modifier = Modifier.testTag(headingTag),
            )
        },
        chips = {
            // The row is pinned left to right so the control keeps its edge; the words
            // inside the chip are handed the reader's own direction back, because what
            // a chip *says* is language and where it sits is not.
            val reading = LocalLayoutDirection.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                CompositionLocalProvider(LocalLayoutDirection provides reading) {
                    CastivioBackChip(
                        height = m.back,
                        pad = m.backPad,
                        fontSize = m.fsBack,
                        label = stringResource(R.string.action_back),
                        onClick = onBack,
                        modifier = Modifier.testTag(backTag),
                    )
                }
            }
        },
    )
}

/** The wordmark against the question — the activation screen's figure, unchanged. */
private const val WORD_RATIO = 0.80f
