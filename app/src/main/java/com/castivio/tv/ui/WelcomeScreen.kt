package com.castivio.tv.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1.6f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.welcome_message),
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 26.sp,
            )
            Surface(
                color = CardSurface,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = portalUrl,
                    color = AccentBlue,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            InfoCard(
                label = stringResource(R.string.device_mac_label),
                value = identity.mac,
            )
            InfoCard(
                label = stringResource(R.string.device_key_label),
                value = identity.key,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onContinue,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                ) {
                    Text(stringResource(R.string.btn_continue), fontSize = 16.sp)
                }
                OutlinedButton(onClick = onXtream, shape = RoundedCornerShape(8.dp)) {
                    Text(stringResource(R.string.btn_xtream), color = Color.White, fontSize = 16.sp)
                }
                OutlinedButton(onClick = onM3u, shape = RoundedCornerShape(8.dp)) {
                    Text(stringResource(R.string.btn_m3u), color = Color.White, fontSize = 16.sp)
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val qr = remember(qrContent) { generateQrBitmap(qrContent, 512) }
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(20.dp),
                ) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(220.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.qr_caption),
                        color = Color(0xFF222222),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.copied)

    Surface(
        color = CardSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Text(text = label, color = Color(0xFFCCCCCC), fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(value))
                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.copy), color = AccentBlue)
                }
            }
        }
    }
}
