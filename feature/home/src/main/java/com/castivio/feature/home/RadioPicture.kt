package com.castivio.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * The picture that says radio, where the channels' board has its preview: headphones and their waves over the application's own
 * indigo.
 *
 * A station has no picture to preview -- a panel sends a name, a logo, a number and a category -- and nothing is drawn to stand in for
 * one, so this is not the station's: it is the section's, the same whichever station sounds. The badge that says it is live is drawn
 * only while something sounds, where the preview draws its own.
 *
 * The picture is the owner's artwork with its black taken out, drawn once and kept as `radio_picture`: the light of the artwork is
 * screened over the indigo, so the ground of the panel is the application's and the headphones are what is lit. It fills the panel and
 * is cropped to it, not stretched. Drawn first in `design/mockups/radio-headphones-ar.png`.
 */
@Composable
internal fun RadioPicture(live: Boolean, m: ChannelsMetrics, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(m.previewRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.radio_picture),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (live) {
            Box(Modifier.align(Alignment.BottomStart).padding(m.osdPad)) { LiveBadge(m) }
        }
    }
}
