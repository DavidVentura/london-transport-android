package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginSheet(flow: HireFlow.Login, onSubmit: (String, String) -> Unit) {
  var email by rememberSaveable { mutableStateOf(flow.email) }
  var password by rememberSaveable { mutableStateOf("") }
  var shown by rememberSaveable { mutableStateOf(false) }
  val complete = email.isNotBlank() && password.isNotBlank()

  SheetBody(
    title = "Log in to Santander Cycles",
    subtitle = when (flow.purpose) {
      LoginPurpose.Unlock -> "Your Santander Cycles account pays for the ride."
      LoginPurpose.Account -> "See your pass and ride history."
    },
  ) {
    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text("Email") },
      singleLine = true,
      enabled = !flow.busy,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text("Password") },
      singleLine = true,
      enabled = !flow.busy,
      visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        IconButton(onClick = { shown = !shown }) {
          Icon(
            if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = if (shown) "Hide password" else "Show password",
          )
        }
      },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { if (complete && !flow.busy) onSubmit(email, password) }),
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    flow.error?.let { InlineError(it, Modifier.padding(top = 16.dp)) }
    Spacer(Modifier.height(24.dp))
    ActionButtons(UiAction("Log in", enabled = complete) { onSubmit(email, password) }, busy = flow.busy, busyLabel = "Logging in…")
  }
}

@Composable
fun VerifySheet(flow: HireFlow.Verify, onSubmit: (String) -> Unit, onResend: () -> Unit) {
  var typed by rememberSaveable(flow.challenge.id) { mutableStateOf("") }

  SheetBody(title = "Check your email", subtitle = "Enter the code sent to ${flow.email} to confirm this phone.") {
    OutlinedTextField(
      value = typed,
      onValueChange = { entry -> typed = entry.filter { it in '0'..'9' } },
      label = { Text("Verification code") },
      supportingText = { Text(flow.challenge.message) },
      singleLine = true,
      enabled = !flow.busy,
      textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank() && !flow.busy) onSubmit(typed) }),
      modifier = Modifier.fillMaxWidth(),
    )
    flow.error?.let { InlineError(it, Modifier.padding(top = 8.dp)) }
    Spacer(Modifier.height(24.dp))
    ActionButtons(
      primary = UiAction("Verify", enabled = typed.isNotBlank()) { onSubmit(typed) },
      secondary = UiAction("Send a new code", onClick = onResend),
      busy = flow.busy,
      busyLabel = "Checking…",
    )
  }
}
