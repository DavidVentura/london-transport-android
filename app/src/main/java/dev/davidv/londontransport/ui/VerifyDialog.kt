package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.davidv.londontransport.santander.SmsChallenge

/** Only closes on the cross: a stray tap outside would drop the SMS the rider is reading. */
@Composable
fun VerifyDialog(
  challenge: SmsChallenge,
  busy: Boolean,
  error: String?,
  onSubmit: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val digits = challenge.pincodeFields.size
  var typed by rememberSaveable { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = {},
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Verify this device", modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
          Icon(Icons.Filled.Close, contentDescription = "Close")
        }
      }
    },
    text = {
      Column {
        Text(challenge.message, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
          value = typed,
          onValueChange = { entry -> typed = entry.filter(Char::isDigit).take(digits) },
          label = { Text("$digits-digit code") },
          singleLine = true,
          enabled = !busy,
          textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 8.sp),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        if (error != null) {
          Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSubmit(typed) }, enabled = typed.length == digits && !busy) {
        if (busy) {
          CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
          Text("Verify")
        }
      }
    },
  )
}
