package com.castivio.feature.home

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.castivio.core.design.components.PosterFrame
import com.castivio.core.design.components.ProviderArtwork
import com.castivio.core.design.theme.CastivioMetrics

/*
 * The pieces of the three small cards' two new faces -- `design/mockups/home-tiles-resume-ar.png` and `home-tiles-load-ar.png`.
 *
 * Both use the card's empty end: a line of 66dp is narrow and wide, and what there is room for is beside the words, not under
 * them. The same violet and the same ink as the large card's button, so the invitation reads as one thing on all four cards.
 */

/**
 * The card's drawing of its press when its section has never been downloaded: the large card's button, shorter, at the end of the
 * line. The card is the control -- this is not focusable, and a press on the card is a press on the section.
 */
@Composable
internal fun TileLoadPill(frame: CastivioMetrics, modifier: Modifier = Modifier) {
    HeroButton(
        label = stringResource(R.string.home_load_tile_button),
        icon = Icons.Rounded.Download,
        frame = frame,
        modifier = modifier,
        height = frame.chip * TILE_PILL_OF_CHIP,
    )
}

/**
 * What was watched last, as a picture at the end of the line: a film's or a show's poster at its own proportions, a station's logo
 * in the square slot the radio's rows give it. [height] is the line's, of which the picture takes a share.
 */
@Composable
internal fun TileThumb(resume: SectionResume, height: Dp, modifier: Modifier = Modifier) {
    when (resume.open) {
        is ResumeOpen.Station -> ProviderArtwork(
            url = resume.artworkUrl,
            description = null,
            modifier = modifier.size(height * TILE_LOGO_OF_LINE),
            seed = resume.title,
        )

        is ResumeOpen.Film, is ResumeOpen.Show -> {
            val poster = height * TILE_POSTER_OF_LINE
            PosterFrame(
                url = resume.artworkUrl,
                seed = resume.title.hashCode(),
                modifier = modifier.size(width = poster * POSTER_ASPECT, height = poster),
            )
        }
    }
}

/** The pill's height as a share of the pill a chip is: a little under, so that it sits in a 66dp line with air above and below. */
private const val TILE_PILL_OF_CHIP = 0.85f

/** A poster is this share of the line's height, a logo a little less: the square needs less to read as large. */
private const val TILE_POSTER_OF_LINE = 0.68f
private const val TILE_LOGO_OF_LINE = 0.6f

/** A poster's width over its height. */
private const val POSTER_ASPECT = 0.68f
