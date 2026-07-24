package com.castivio.tv.ui

import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.tv.R
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.tv.data.PlaylistSource
import com.castivio.tv.data.SourceStore

@Composable
private fun castivioFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = CastivioTheme.colors.primary,
    unfocusedBorderColor = Color(0x66FFFFFF),
    focusedLabelColor = CastivioTheme.colors.primary,
    unfocusedLabelColor = Color(0xFFBBBBBB),
    cursorColor = CastivioTheme.colors.primary,
)

@Composable
fun XtreamScreen(onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val requiredMsg = stringResource(R.string.field_required)
    val invalidUrlMsg = stringResource(R.string.invalid_url)
    val savedMsg = stringResource(R.string.saved)

    SourceForm(title = stringResource(R.string.xtream_title)) {
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text(stringResource(R.string.xtream_server)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = castivioFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.xtream_username)) },
            singleLine = true,
            colors = castivioFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.xtream_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = castivioFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        FormButtons(
            onSave = {
                when {
                    server.isBlank() || username.isBlank() || password.isBlank() ->
                        Toast.makeText(context, requiredMsg, Toast.LENGTH_SHORT).show()
                    !Patterns.WEB_URL.matcher(server.trim()).matches() ->
                        Toast.makeText(context, invalidUrlMsg, Toast.LENGTH_SHORT).show()
                    else -> {
                        SourceStore.save(
                            context,
                            PlaylistSource.Xtream(server.trim(), username.trim(), password),
                        )
                        Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                }
            },
            onCancel = onCancel,
        )
    }
}

@Composable
fun M3uScreen(onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }

    val requiredMsg = stringResource(R.string.field_required)
    val invalidUrlMsg = stringResource(R.string.invalid_url)
    val savedMsg = stringResource(R.string.saved)

    SourceForm(title = stringResource(R.string.m3u_title)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.m3u_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = castivioFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        FormButtons(
            onSave = {
                when {
                    url.isBlank() ->
                        Toast.makeText(context, requiredMsg, Toast.LENGTH_SHORT).show()
                    !Patterns.WEB_URL.matcher(url.trim()).matches() ->
                        Toast.makeText(context, invalidUrlMsg, Toast.LENGTH_SHORT).show()
                    else -> {
                        SourceStore.save(context, PlaylistSource.M3u(url.trim()))
                        Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                }
            },
            onCancel = onCancel,
        )
    }
}

@Composable
private fun SourceForm(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(
            modifier = Modifier.width(480.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun FormButtons(onSave: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onSave,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CastivioTheme.colors.primary),
        ) {
            Text(stringResource(R.string.save), fontSize = 16.sp)
        }
        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(8.dp)) {
            Text(stringResource(R.string.cancel), color = Color.White, fontSize = 16.sp)
        }
    }
}
