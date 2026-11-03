package com.castivio.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.castivio.core.common.StreamQuality
import com.castivio.core.design.R
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing

/**
 * The stream-quality indicator: a small Wi-Fi mark whose lit arcs and colour say how well the stream is arriving.
 *
 * ## One mark in every player
 *
 * The same glyph and the same four states wherever a stream plays -- the channels, the films, the shows and the radio -- so
 * a viewer learns it once. It is drawn at the size of the other icons of its row: [Sizing.iconMd] on a phone and a tablet,
 * [Sizing.iconLg] on a television, where everything is read from the sofa. It is a component of the design system and is declared here and nowhere else:
 * a player that drew its own would be a second indicator that agreed with this one until the first edit to either.
 *
 * ## Four states, told two ways
 *
 * The colour is the quick word -- green, light green, orange, red -- and the **number of arcs lit is the same word again**:
 * three, two, one, none, with the dot always lit. A colour alone is not enough for a viewer who cannot tell them apart, and
 * the glyph is read the same in a photograph of the screen in black and white. The arcs that are not lit are drawn faintly
 * in the same hue, so the mark is always the whole Wi-Fi shape and only the signal in it changes.
 *
 * ## What it is not
 *
 * Not a control. It takes no focus and no press, so a remote never lands on it and a thumb never has to avoid it; it is
 * read by a screen reader as a sentence, through its description. The same sentence is the glyph's name in every language
 * the application has. Drawn only for a [quality]: with none the slot keeps its size and shows nothing, so the controls
 * beside it do not move when a rating arrives or goes.
 *
 * Symmetric about its own vertical axis, so it reads the same in both directions and mirrors nothing.
 */
@Composable
fun StreamQualityIcon(
    quality: StreamQuality?,
    modifier: Modifier = Modifier,
    size: Dp = if (CastivioTheme.device.isTv) Sizing.iconLg else Sizing.iconMd,
) {
    val colors = CastivioTheme.colors
    val description = quality?.let {
        stringResource(
            when (it) {
                StreamQuality.EXCELLENT -> R.string.stream_quality_excellent
                StreamQuality.GOOD -> R.string.stream_quality_good
                StreamQuality.POOR -> R.string.stream_quality_poor
                StreamQuality.BAD -> R.string.stream_quality_bad
            },
        )
    }
    val hue: Color = when (quality) {
        StreamQuality.EXCELLENT -> colors.signalExcellent
        StreamQuality.GOOD -> colors.signalGood
        StreamQuality.POOR -> colors.signalPoor
        StreamQuality.BAD -> colors.signalBad
        null -> Color.Transparent
    }
    val lit = when (quality) {
        StreamQuality.EXCELLENT -> ARCS
        StreamQuality.GOOD -> ARCS - 1
        StreamQuality.POOR -> ARCS - 2
        StreamQuality.BAD, null -> 0
    }
    val slot = Modifier.size(size)
    Canvas(
        modifier = modifier
            .then(slot)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        if (quality == null) return@Canvas
        val width = this.size.width
        val stroke = Stroke(width = width * STROKE_OF_SIZE, cap = StrokeCap.Round)
        val centre = Offset(width / 2f, this.size.height * CENTRE_Y)
        // Three arcs, the nearest first, each a quarter of a circle opening upward.
        for (arc in 0 until ARCS) {
            val radius = width * RADIUS_STEP * (arc + 1)
            drawArc(
                color = if (arc < lit) hue else hue.copy(alpha = UNLIT_ALPHA),
                startAngle = ARC_START,
                sweepAngle = ARC_SWEEP,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = stroke,
            )
        }
        drawCircle(color = hue, radius = width * DOT_OF_SIZE, center = centre)
    }
}

/** Arcs in the mark. The indicator has four states, so it has the dot and three of these. */
private const val ARCS = 3

/**
 * Where the arcs open from, down the mark: the dot's height as a share of it. With [RADIUS_STEP] the whole drawing -- the
 * outer arc's rounded ends, its top, the dot's foot -- lies inside the slot with the same margin above and below.
 */
private const val CENTRE_Y = 0.80f

/**
 * Each arc's radius step, and the stroke and the dot, as shares of the mark's width. The outer arc is three steps out, and
 * its ends reach sideways by that radius times the sine of 45 degrees plus half a stroke: at this step, half the slot.
 */
private const val RADIUS_STEP = 0.21f
private const val STROKE_OF_SIZE = 0.10f
private const val DOT_OF_SIZE = 0.075f

/** A quarter circle opening upward: from the upper left, a quarter turn clockwise. */
private const val ARC_START = 225f
private const val ARC_SWEEP = 90f

/** What an arc that is not lit keeps of its hue, so the whole mark is always there. */
private const val UNLIT_ALPHA = 0.28f
