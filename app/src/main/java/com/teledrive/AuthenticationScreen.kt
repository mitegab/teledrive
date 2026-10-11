package com.teledrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.teledrive.core.model.AuthorizationState

@Composable
fun AuthenticationScreen(
    state: AuthorizationState,
    errorMessage: String?,
    onPhoneNumber: (String) -> Unit,
    onCode: (String) -> Unit,
    onPassword: (String) -> Unit,
    onEmail: (String) -> Unit,
    onEmailCode: (String) -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
) {
    var value by rememberSaveable(state::class.qualifiedName) { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Cancel") }
                Text("Connect Telegram", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Text(authenticationTitle(state), style = MaterialTheme.typography.headlineMedium)
            Text(authenticationDescription(state), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (state) {
                AuthorizationState.WaitingForTdlibParameters -> {
                    CircularProgressIndicator()
                    Text("Preparing a private Telegram session…", style = MaterialTheme.typography.bodyMedium)
                }
                AuthorizationState.WaitingForPhoneNumber -> AuthField(value, { value = it }, "Phone number", "For example, +251…", KeyboardType.Phone, onPhoneNumber)
                is AuthorizationState.WaitingForCode -> AuthField(value, { value = it }, "Authentication code", state.hint ?: "The code sent by Telegram", KeyboardType.Number, onCode)
                is AuthorizationState.WaitingForPassword -> AuthField(value, { value = it }, "Two-step verification password", state.hint ?: "Your Telegram password", KeyboardType.Password, onPassword, isPassword = true)
                is AuthorizationState.WaitingForEmailAddress -> AuthField(value, { value = it }, "Email address", "Used only when Telegram requests it", KeyboardType.Email, onEmail)
                is AuthorizationState.WaitingForEmailCode -> AuthField(value, { value = it }, "Email verification code", state.hint ?: "The code sent by Telegram", KeyboardType.Number, onEmailCode)
                is AuthorizationState.WaitingForOtherDeviceConfirmation -> {
                    Text("Approve the Telegram login on another signed-in device.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { if (state.link.isNotBlank()) uriHandler.openUri(state.link) }) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Open confirmation link")
                    }
                }
                is AuthorizationState.WaitingForRegistration,
                AuthorizationState.WaitingForPremiumPurchase,
                -> {
                    Text("This Telegram account state needs to be completed in an official Telegram client before TeleDrive can continue.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { uriHandler.openUri("https://telegram.org/apps") }) { Text("Open Telegram apps") }
                }
                else -> Unit
            }

            if (errorMessage != null) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Text("Check your connection and try again. Never share an authentication code or password with anyone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text("TeleDrive uses official TDLib authorization. It does not ask for a session token.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
    keyboardType: KeyboardType,
    onSubmit: (String) -> Unit,
    isPassword: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            supportingText = { Text(supporting) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            shape = RoundedCornerShape(16.dp),
        )
        Button(onClick = { onSubmit(value) }, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("Continue")
        }
    }
}

private fun authenticationTitle(state: AuthorizationState): String = when (state) {
    AuthorizationState.WaitingForPhoneNumber -> "Enter your phone number"
    is AuthorizationState.WaitingForCode -> "Enter the Telegram code"
    is AuthorizationState.WaitingForPassword -> "Enter your password"
    is AuthorizationState.WaitingForEmailAddress -> "Verify your email address"
    is AuthorizationState.WaitingForEmailCode -> "Enter the email code"
    is AuthorizationState.WaitingForOtherDeviceConfirmation -> "Confirm on another device"
    is AuthorizationState.WaitingForRegistration -> "Finish setup in Telegram"
    AuthorizationState.WaitingForPremiumPurchase -> "Telegram needs attention"
    AuthorizationState.WaitingForTdlibParameters -> "Preparing secure connection"
    else -> "Sign in securely"
}

private fun authenticationDescription(state: AuthorizationState): String = when (state) {
    AuthorizationState.WaitingForPhoneNumber -> "Use the international format. Telegram will deliver a verification code through its supported channel."
    is AuthorizationState.WaitingForCode -> "The code belongs to your Telegram account and is never written to TeleDrive storage."
    is AuthorizationState.WaitingForPassword -> "Your Telegram two-step password is handled only by TDLib during this authorization step."
    is AuthorizationState.WaitingForEmailAddress, is AuthorizationState.WaitingForEmailCode -> "Telegram may ask for an email verification step for this account."
    else -> "TeleDrive is waiting for Telegram's authorization state."
}
