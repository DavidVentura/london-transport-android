package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Reached only by asking for a bike, and it hands the rider back to that same request once the
 * account answers — so it says which dock is waiting.
 */
@Composable
fun LoginSheet(
  stationName: String,
  busy: Boolean,
  error: String?,
  onSubmit: (String, String) -> Unit,
) {
  var email by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  val complete = email.isNotBlank() && password.isNotBlank()

  Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
    Text("Santander Cycles account", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Log in to take a bike out at $stationName.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )

    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text("Email") },
      singleLine = true,
      enabled = !busy,
      keyboardOptions =
        KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text("Password") },
      singleLine = true,
      enabled = !busy,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions =
        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )

    if (error != null) {
      Text(
        error,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp),
      )
    }

    Button(
      onClick = { onSubmit(email, password) },
      enabled = complete && !busy,
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
          CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp).padding(end = 4.dp),
          )
        }
        Text(if (busy) "Logging in…" else "Log in", modifier = Modifier.padding(start = 8.dp))
      }
    }
    // The backend times its own login out often enough that one attempt regularly is not enough.
    if (busy) {
      Text(
        "This can take a few tries; the app keeps retrying.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}
