package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** In-app card entry has not completed a live bank verification yet, so only debug builds offer it. */
@Composable
fun DebugCardRoute(onClick: () -> Unit) {
  TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Debug: pay with a card in the app") }
}
