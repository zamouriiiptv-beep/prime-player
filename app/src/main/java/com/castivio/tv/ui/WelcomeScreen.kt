package com.castivio.tv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.castivio.tv.R
import com.castivio.tv.data.DeviceIdentity
import com.castivio.tv.util.generateQrBitmap

@Composable
fun WelcomeScreen(
    identity: DeviceIdentity,
    onContinue: () -> Unit,
    onXtream: () -> Unit,
    onM3u: () -> Unit,
) {
    val portalUrl = stringResource(R.string.portal_url)
    val qrContent = "$portalUrl/activate?mac=${identity.mac}&key=${identity.key}"

    // Force RTL so the QR always sits on the right and the info on the left,
    // matching the reference regardless of the device locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 1500.dp)
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // QR is the first child => right side under RTL.
                Entrance(delayMillis = 220, modifier = Modifier.weight(1f)) {
                    QrCard(qrContent)
                }
                // Info column => left side.
                Entrance(delayMillis = 0, modifier = Modifier.weight(1.55f)) {
                    InfoColumn(identity, portalUrl, onContinue, onXtream, onM3u)
                }
            }
        }
    }
}

@Composable
private fun InfoColumn(
    identity: DeviceIdentity,
    portalUrl: String,
    onContinue: () -> Unit,
    onXtream: () -> Unit,
    onM3u: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(
            painter = painterResource(R.drawable.castivio_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.height(88.dp),
        )
        Text(
            text = stringResource(R.string.welcome_message),
            color = Color(0xFFCBCBDD),
            fontSize = 15.sp,
            lineHeight = 24.sp,
        )
        LinkCapsule(url = portalUrl)

        InfoGlassCard(
            label = stringResource(R.string.device_mac_label),
            value = identity.mac,
        )
        InfoGlassCard(
            label = stringResource(R.string.device_key_label),
            value = identity.key,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PremiumButton(text = stringResource(R.string.btn_continue), primary = true, onClick = onContinue)
            PremiumButton(text = stringResource(R.string.btn_xtream), primary = false, onClick = onXtream)
            PremiumButton(text = stringResource(R.string.btn_m3u), primary = false, onClick = onM3u)
        }
    }
}

@Composable
private fun InfoGlassCard(label: String, value: String) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = Color(0xFFAEAEC2), fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            CopyButton(value = value)
        }
    }
}

@Composable
private fun QrCard(qrContent: String) {
    // Subtle continuous floating motion.
    val transition = rememberInfiniteTransition(label = "qr")
    val floatDp by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float",
    )
    val density = LocalDensity.current

    Box(contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer { translationY = with(density) { floatDp.dp.toPx() } }
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White)
                .padding(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val qr = remember(qrContent) { generateQrBitmap(qrContent, 640) }
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(200.dp),
                )
                // Small brand mark in the QR centre.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    CastivioLogo(size = 38.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.qr_caption),
                color = Color(0xFF1C1C28),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Fade-in + slide-up entrance for a section, with an optional stagger delay. */
@Composable
private fun Entrance(
    delayMillis: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val progress by animateFloatAsState(
        targetValue = if (appear) 1f else 0f,
        animationSpec = tween(durationMillis = 620, delayMillis = delayMillis, easing = FastOutSlowInEasing),
        label = "entrance",
    )
    val density = LocalDensity.current
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = with(density) { (1f - progress) * 34.dp.toPx() }
        },
    ) { content() }
}
