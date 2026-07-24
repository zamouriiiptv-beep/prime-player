package com.castivio.tv.ui

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.tv.R
import com.castivio.tv.ui.components.Entrance
import com.castivio.tv.ui.components.GlassCard
import com.castivio.tv.ui.components.focusLift
import com.castivio.tv.ui.theme.CastivioTheme
import com.castivio.tv.ui.theme.Palette
import com.castivio.tv.data.DeviceIdentity
import com.castivio.tv.util.generateQrBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Purple = Palette.Violet50
private val Teal = Palette.Aqua
private val Muted = Palette.Muted

@Composable
fun WelcomeScreen(
    identity: DeviceIdentity,
    onRefresh: () -> Unit,
    onXtream: () -> Unit,
    onM3u: () -> Unit,
    onSupport: () -> Unit,
    onLanguage: () -> Unit,
    onExit: () -> Unit,
) {
    val portalUrl = stringResource(R.string.portal_url)
    val qrContent = "$portalUrl/activate?mac=${identity.mac}&key=${identity.key}"

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TopBar(onLanguage = onLanguage)
            Header()
            Entrance(order = 0) { MainRow(identity, qrContent) }
            Entrance(order = 1) { SecondaryMethods(onXtream, onM3u) }
            Entrance(order = 2) { BottomToolbar(identity, onRefresh, onSupport, onLanguage, onExit) }
            Footer(identity)
        }
    }
}

// -------------------------------------------------------------------- Top bar

@Composable
private fun TopBar(onLanguage: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // First child => right under RTL: language pill.
        val interaction = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .focusLift(1.05f)
                .clip(RoundedCornerShape(50))
                .background(Color(0x14FFFFFF))
                .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0x24FFFFFF)), RoundedCornerShape(50))
                .clickable(interaction, null) { onLanguage() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Icon(Icons.Rounded.Language, null, tint = Color.White, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lang_button), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        // Second child => left: logo lockup.
        Image(
            painter = painterResource(R.drawable.castivio_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.height(58.dp),
        )
    }
}

// --------------------------------------------------------------------- Header

@Composable
private fun Header() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.activate_title),
            color = Color.White,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.activate_subtitle),
            color = Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------ Main row

@Composable
private fun MainRow(identity: DeviceIdentity, qrContent: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // First child => right: QR side card (secondary).
        QrSideCard(qrContent, modifier = Modifier.weight(1f).fillMaxHeight())
        // Second child => left/centre: MAC hero (primary, ~70%).
        MacHeroCard(identity, modifier = Modifier.weight(2.7f))
    }
}

@Composable
private fun MacHeroCard(identity: DeviceIdentity, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.copied)

    GlassCard(modifier = modifier, shape = RoundedCornerShape(26.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // MAC section (right in RTL).
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mac_label_pre), color = Color(0xFFDDDDEA), fontSize = 17.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("MAC", color = CastivioTheme.colors.primary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.mac_label_post), color = Color(0xFFDDDDEA), fontSize = 17.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = identity.mac,
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    FilledCopyButton {
                        clipboard.setText(AnnotatedString(identity.mac))
                        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = CastivioTheme.colors.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mac_info), color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            // Divider.
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color(0x1FFFFFFF)),
            )
            // Recommended badge (left in RTL).
            Column(
                modifier = Modifier.width(180.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF6E4BD8), Color(0xFF3D6BFF)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.recommended),
                    color = Purple,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.recommended_help),
                    color = Muted,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Purple, CastivioTheme.colors.primary))),
                )
            }
        }
    }
}

@Composable
private fun FilledCopyButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "cp")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF4C9BFF), Color(0xFF2E6BFF))))
            .clickable(interaction, null) { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Icon(Icons.Rounded.ContentCopy, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.copy), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------- QR card

@Composable
private fun QrSideCard(qrContent: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "qr")
    val floatDp by transition.animateFloat(
        -4f, 4f,
        infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "f",
    )
    val density = LocalDensity.current

    GlassCard(modifier = modifier, shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = with(density) { floatDp.dp.toPx() } }
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                val qr = remember(qrContent) { generateQrBitmap(qrContent, 512) }
                Image(qr.asImageBitmap(), null, modifier = Modifier.size(128.dp))
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) { CastivioLogo(size = 26.dp) }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.qr_title), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.qr_caption),
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ----------------------------------------------------------- Secondary methods

@Composable
private fun SecondaryMethods(onXtream: () -> Unit, onM3u: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.other_methods),
            color = Muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // First child => right: M3U.
            SecondaryCard(
                title = stringResource(R.string.card_m3u_title),
                desc = stringResource(R.string.card_m3u_desc),
                icon = Icons.Rounded.Description,
                tint = Teal,
                onClick = onM3u,
                modifier = Modifier.weight(1f),
            )
            // Second child => left: Xtream.
            SecondaryCard(
                title = stringResource(R.string.card_xtream_title),
                desc = stringResource(R.string.card_xtream_desc),
                icon = Icons.Rounded.Lock,
                tint = Purple,
                onClick = onXtream,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SecondaryCard(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    GlassCard(modifier = modifier.focusLift(1.03f), shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interaction, null) { onClick() }
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .border(androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.35f)), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(desc, color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.ChevronLeft, null, tint = Muted, modifier = Modifier.size(26.dp))
        }
    }
}

// ------------------------------------------------------------ Bottom toolbar

@Composable
private fun BottomToolbar(
    identity: DeviceIdentity,
    onRefresh: () -> Unit,
    onSupport: () -> Unit,
    onLanguage: () -> Unit,
    onExit: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val allCopied = stringResource(R.string.all_data_copied)

    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // RTL: first child at right => Exit; visual left→right ends at Refresh.
            ToolbarItem(Icons.Rounded.PowerSettingsNew, stringResource(R.string.toolbar_exit), Color(0xFFFF5A5A), onExit)
            ToolbarItem(Icons.Rounded.Language, stringResource(R.string.toolbar_language), Color.White, onLanguage)
            ToolbarItem(Icons.Rounded.SupportAgent, stringResource(R.string.toolbar_support), Color.White, onSupport)
            ToolbarItem(Icons.Rounded.ContentCopy, stringResource(R.string.toolbar_copy_all), Color.White) {
                clipboard.setText(AnnotatedString("MAC: ${identity.mac}\nDevice Key: ${identity.key}"))
                Toast.makeText(context, allCopied, Toast.LENGTH_SHORT).show()
            }
            ToolbarItem(Icons.Rounded.Refresh, stringResource(R.string.toolbar_refresh), CastivioTheme.colors.primary, onRefresh)
        }
    }
}

@Composable
private fun ToolbarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusLift(1.06f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interaction, null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (tint == Color.White) Color(0xFFE4E4EE) else tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// --------------------------------------------------------------------- Footer

@Composable
private fun Footer(identity: DeviceIdentity) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "1.0.0"
    }
    val date = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date()) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterItem("Device Key:", identity.key, CastivioTheme.colors.primary)
        FooterDot()
        FooterItem("App Version:", version, CastivioTheme.colors.primary)
        FooterDot()
        FooterItem("Date:", date, CastivioTheme.colors.primary)
    }
}

@Composable
private fun FooterItem(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.width(5.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FooterDot() {
    Box(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .size(3.dp)
            .clip(CircleShape)
            .background(Muted),
    )
}
