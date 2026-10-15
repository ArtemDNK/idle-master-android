package com.idlemaster.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.idlemaster.android.R
import com.idlemaster.android.data.Settings
import com.idlemaster.android.steam.AuthPrompt
import com.idlemaster.android.steam.SteamConnection
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(steam: SteamConnection, settings: Settings) {
    var username by remember { mutableStateOf(settings.lastAccountName) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val prompt by steam.authPrompt.collectAsState()
    val status by steam.status.collectAsState()
    val scope = rememberCoroutineScope()

    fun submit() {
        if (username.isBlank() || password.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            val r = steam.login(username, password)
            busy = false
            r.onFailure { if (it.message != "cancelled") error = it.message }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = SteamColors.Blue,
        )
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.login_hint),
            style = MaterialTheme.typography.bodySmall,
            color = SteamColors.TextDim,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        val shownError = error ?: status.lastError.takeIf { !busy && settings.account == null }
        if (shownError != null) {
            Text(shownError, color = SteamColors.Error, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { submit() }, enabled = !busy && username.isNotBlank() && password.isNotEmpty()) {
                Text(stringResource(R.string.sign_in))
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                TextButton(onClick = { steam.cancelLogin(); busy = false }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }

    when (val p = prompt) {
        null -> Unit
        AuthPrompt.ChooseConfirmation -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.guard_choose_title)) },
            text = { Text(stringResource(R.string.guard_choose_text)) },
            confirmButton = {
                TextButton(onClick = { steam.chooseConfirmation(approveInApp = true) }) {
                    Text(stringResource(R.string.guard_approve_in_app))
                }
            },
            dismissButton = {
                TextButton(onClick = { steam.chooseConfirmation(approveInApp = false) }) {
                    Text(stringResource(R.string.guard_enter_code))
                }
            },
        )
        AuthPrompt.WaitingForApproval -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.guard_choose_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.guard_waiting))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { steam.cancelLogin(); busy = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
        is AuthPrompt.DeviceCode -> CodeDialog(
            title = stringResource(R.string.guard_device_code),
            subtitle = null,
            wrong = p.previousWrong,
            onSubmit = steam::submitCode,
            onCancel = { steam.cancelLogin(); busy = false },
        )
        is AuthPrompt.EmailCode -> CodeDialog(
            title = stringResource(R.string.guard_email_code),
            subtitle = p.email?.let { stringResource(R.string.guard_email_sent, it) },
            wrong = p.previousWrong,
            onSubmit = steam::submitCode,
            onCancel = { steam.cancelLogin(); busy = false },
        )
    }
}

@Composable
private fun CodeDialog(
    title: String,
    subtitle: String?,
    wrong: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
                if (wrong) Text(stringResource(R.string.guard_wrong_code), color = SteamColors.Error)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(8) },
                    label = { Text(stringResource(R.string.code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = code.length >= 5) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
