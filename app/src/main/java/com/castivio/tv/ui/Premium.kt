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

// Lighter, airier glass (reduced fill => reads as less blur, more transparent).
private val GlassFillBrush = Brush.verticalGradient(
    listOf(Color(0x17FFFFFF), Color(0x08FFFFFF)),
)
// More transparent hairline border.
private val GlassBorderBrush = Brush.verticalGradient(
    listOf(Color(0x3DFFFFFF), Color(0x12FFFFFF)),
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
            .shadow(9.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Rounded.Link, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(9.dp))
        Text(url, color = Color(0xF2FFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
            .focusLift(1.10f)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0x14FFFFFF))
            .border(BorderStroke(1.dp, Color(0x24FFFFFF)), RoundedCornerShape(9.dp))
            .clickable(interaction, null) {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = copiedMsg, tint = Color(0xCC3D8BFF), modifier = Modifier.size(17.dp))
    }
}

// ---- Premium buttons -----------------------------------------------------

// Smoother three-stop blue gradient.
private val PrimaryGradient = Brush.horizontalGradient(
    listOf(Color(0xFF5AA2FF), Color(0xFF3D82FF), Color(0xFF2C67F0)),
)

@Composable
fun PremiumButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(180), label = "btnScale")
    val elevation by animateDpAsState(if (focused) 10.dp else 2.dp, tween(180), label = "btnElev")
    val glow = if (primary) Color(0x772E6BFF) else Color(0x33FFFFFF)
    val borderColor by animateColorAsState(
        if (focused) Color(0x80FFFFFF) else Color(0x24FFFFFF), tween(180), label = "btnBorder",
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
                else Modifier.background(Color(0x14FFFFFF)).border(BorderStroke(1.dp, borderColor), shape)
            )
            .clickable(interaction, null) { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
