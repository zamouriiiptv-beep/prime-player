package com.castivio.feature.activation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castivio.core.common.locale.CastivioLanguage
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing

/**
 * Choosing one of the thirty-seven.
 *
 * An overlay over the activation screen, which does not move behind it. The
 * design and every number here are approved: `design/mockups/language-picker.html`
 * is the drawing, `design/activation-spec.md` §10.5 the contract.
 *
 * Three decisions that look like details and are not.
 *
 * **A grid, not a list.** The phone is landscape at 393dp, where height is the
 * scarce dimension and a language's name is at most sixteen characters. Measured:
 * three columns show 15 of the 37 at once — 2.5 screens; one column would show 5
 * and take 7.4. A television takes four columns and shows 24.
 *
 * **No search field.** The list is already indexed, by the rule that each
 * language appears under its own name — العربية among Latin names is not
 * something anyone reads to find, and a reader is looking for their own letters.
 * An alphabetical list of English exonyms would need search. What search costs is
 * an on-screen keyboard on a remote, to save two directional presses.
 *
 * **Focus is not selection**, and on a television both are true of a row at once.
 * Selection is a filled surface, a heavier name and a check — three cues, none of
 * them hue alone. Focus is the ring the rest of Castivio uses, drawn outside the
 * row's own surface so it still reads on a selected row.
 *
 * **Public, because the licence screen offers the same control.** Two screens
 * now carry a language chip and there must be one picker behind both — invariant
 * 6, and a second one would drift the moment somebody preferred four columns.
 * It stays in this module rather than moving to `:core:design` because it owns
 * five string resources in 38 languages and a shared component may not own copy;
 * the day a third screen needs it, a `:feature:language` module is the answer
 * and the strings move with it. Applying the choice remains `:app`'s, since it
 * means wrapping the `Context` an activity was built on.
 */
@Composable
fun LanguagePicker(
    selected: CastivioLanguage,
    onPick: (CastivioLanguage) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    val columns = if (tv) TV_COLUMNS else PHONE_COLUMNS
    val languages = remember { CastivioLanguage.ordered }
    val gridState = rememberLazyGridState()
    val selectedFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(if (tv) Radius.xxl else Radius.xl)

    // Opens on the language Castivio is in. With 37 entries and no search, the
    // one thing a returning user reliably wants is to see where they already are.
    LaunchedEffect(selected) {
        gridState.scrollToItem(languages.indexOf(selected).coerceAtLeast(0) / columns)
        runCatching { selectedFocus.requestFocus() }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            // The scrim absorbs presses instead of letting them through to the
            // screen underneath. That is what makes this modal rather than a
            // decoration drawn over something still live.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (tv) TV_INSET_H else PHONE_INSET_H,
                    vertical = if (tv) TV_INSET_V else PHONE_INSET_V,
                )
                .clip(shape)
                .background(colors.backgroundElevated)
                .border(BorderStroke(1.dp, colors.glassBorderSoft), shape)
                // Presses inside the panel are the panel's, not the scrim's.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            PickerHeader(tv = tv, onDismiss = onDismiss)

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    horizontal = if (tv) Spacing.xl else Spacing.lg,
                    vertical = Spacing.md,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (tv) Spacing.md else Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                items(languages, key = { it.name }) { language ->
                    LanguageRow(
                        language = language,
                        isSelected = language == selected,
                        tv = tv,
                        onPick = { onPick(language) },
                        modifier = if (language == selected) {
                            Modifier.focusRequester(selectedFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(tv: Boolean, onDismiss: () -> Unit) {
    val colors = CastivioTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (tv) Spacing.xl else Spacing.lg,
                    vertical = Spacing.md,
                ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.language),
                style = if (tv) CastivioType.titleLarge else CastivioType.bodyMedium,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.language_count, CastivioLanguage.COUNT),
                style = CastivioType.bodySmall,
                color = colors.onBackgroundVariant,
            )
            Box(Modifier.weight(1f))

            // A television already has a Back button, and a second way out is a
            // target the D-pad must walk past on its way to the list. So the
            // remote is told what to press rather than given a control to reach.
            if (tv) {
                Text(
                    text = stringResource(R.string.language_back_hint),
                    style = CastivioType.bodySmall,
                    color = colors.onBackgroundVariant,
                )
            } else {
                CastivioIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.language_close),
                    onClick = onDismiss,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

@Composable
private fun LanguageRow(
    language: CastivioLanguage,
    isSelected: Boolean,
    tv: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.sm)
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val selectedLabel = stringResource(R.string.language_selected)

    // Two states, two channels. The border animates to the focus ring when the
    // remote arrives and back to the selection's own edge when it leaves, so a
    // row that is both never has to choose which fact to show.
    val border by animateColorAsState(
        when {
            focused -> colors.focusRing
            isSelected -> colors.selectedBorder
            else -> Color.Transparent
        },
        Motion.focusSpec(),
        label = "languageBorder",
    )

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) TV_ROW else PHONE_ROW)
            .castivioFocusScale(Motion.focusScaleIcon, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(if (isSelected) colors.selectedFill else Color.Transparent)
            .border(BorderStroke(if (tv) 2.dp else 1.dp, border), shape)
            .clickable(interaction, indication = null, onClick = onPick)
            .padding(horizontal = if (tv) Spacing.md else Spacing.sm)
            // One announcement for the row, carrying the name and whether this is
            // the current language. Two children announcing separately is how a
            // reader ends up saying "check mark, English".
            .clearAndSetSemantics {
                contentDescription = if (isSelected) {
                    "${language.nativeName}, $selectedLabel"
                } else {
                    language.nativeName
                }
                selected = isSelected
            },
        horizontalArrangement = Arrangement.spacedBy(if (tv) Spacing.sm else Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(TICK), contentAlignment = Alignment.Center) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.focusRing,
                )
            }
        }
        Text(
            text = isolate(language.nativeName),
            style = if (tv) CastivioType.titleMedium else CastivioType.bodyLarge,
            color = if (isSelected) colors.onBackground else colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A language's own name, isolated from the paragraph around it.
 *
 * `FIRST STRONG ISOLATE` opens the run and `POP DIRECTIONAL ISOLATE` closes it:
 * the run takes whichever base direction its own first strong character implies,
 * and it cannot reorder anything outside itself. This is the character-level form
 * of HTML's `<bdi>`, and it is what keeps an Arabic name from turning a row round
 * in an English list, or an English name from doing it in an Arabic one.
 *
 * The cell keeps the interface's direction and alignment. Putting the direction
 * on the cell instead was tried in the mockup and pushed Arabic names to the far
 * end of their column — losing the one alignment a scannable list depends on, and
 * it would have done the same to every Latin name once the interface turned
 * round.
 */
private fun isolate(name: String): String = "⁨$name⁩"

private const val PHONE_COLUMNS = 3
private const val TV_COLUMNS = 4
private val PHONE_ROW = 48.dp
private val TV_ROW = 56.dp
private val TICK = 20.dp
private val PHONE_INSET_H = 92.dp
private val PHONE_INSET_V = 12.dp
private val TV_INSET_H = 64.dp
private val TV_INSET_V = 34.dp
