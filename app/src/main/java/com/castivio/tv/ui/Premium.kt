package com.castivio.tv.ui

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.tv.R

// ---- Shared glass styling ------------------------------------------------

private val GlassFillBrush = Brush.verticalGradient(
    listOf(Color(0x24FFFFFF), Color(0x0FFFFFFF)),
)
private val GlassBorderBrush = Brush.verticalGradient(
    listOf(Color(0x59FFFFFF), Color(0x1AFFFFFF)),
)

/** Lifts and scales an element when it gains D-pad / pointer focus (TV + touch). */
fun Modifier.focusLift(scaleFocused: Float = 1.06f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) scaleFocused else 1f, tween(180), label = "lift")
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A floating frosted-glass panel: translucent fill, gradient hairline border, soft shadow. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 22,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    Box(
        modifier
            .shadow(18.dp, shape, ambientColor = Color(0x66000000), spotColor = Color(0x66000000))
            .clip(shape)
            .background(GlassFillBrush)
            .border(BorderStroke(1.dp, GlassBorderBrush), shape),
    ) { content() }
}

// ---- Website URL capsule -------------------------------------------------

@Composable
fun LinkCapsule(url: String) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(GlassFillBrush)
            .border(BorderStroke(1.dp, GlassBorderBrush), shape)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Icon(Icons.Rounded.Link, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(url, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- Copy icon button ----------------------------------------------------

@Composable
fun CopyButton(value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMsg = androidx.compose.ui.res.stringResource(R.string.copied)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .focusLift(1.18f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x1FFFFFFF))
            .border(BorderStroke(1.dp, Color(0x33FFFFFF)), RoundedCornerShape(10.dp))
            .clickable(interaction, null) {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = copiedMsg, tint = AccentBlue, modifier = Modifier.size(20.dp))
    }
}

// ---- Premium buttons -----------------------------------------------------

private val PrimaryGradient = Brush.horizontalGradient(listOf(Color(0xFF4C9BFF), Color(0xFF2E6BFF)))

@Composable
fun PremiumButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(180), label = "btnScale")
    val elevation by animateDpAsState(if (focused) 16.dp else 4.dp, tween(180), label = "btnElev")
    val glow = if (primary) Color(0xAA2E6BFF) else Color(0x55FFFFFF)
    val borderColor by animateColorAsState(
        if (focused) Color(0x99FFFFFF) else Color(0x33FFFFFF), tween(180), label = "btnBorder",
    )
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .shadow(elevation, shape, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .then(
                if (primary) Modifier.background(PrimaryGradient)
                else Modifier.background(Color(0x1FFFFFFF)).border(BorderStroke(1.dp, borderColor), shape)
            )
            .clickable(interaction, null) { onClick() }
            .padding(horizontal = 26.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
