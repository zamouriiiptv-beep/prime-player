package com.castivio.core.design.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection

/**
 * The alignment that sets [text] against the page's start edge, whichever way its own words run.
 *
 * For a page's own words it is `Start`, as it always was. For a provider's -- an English name and plot on an Arabic page
 * -- it is `End`, which is the right edge of an English paragraph, so the page has one edge and the words keep their
 * order. See [TextEdge], which makes the choice.
 */
@Composable
fun startEdgeAlign(text: String): TextAlign =
    if (TextEdge.againstEnd(LocalLayoutDirection.current == LayoutDirection.Rtl, text)) TextAlign.End else TextAlign.Start
