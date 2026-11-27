package dev.davidv.londontransport.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.domain.MapFeature

@Composable
fun PinSheet(feature: MapFeature, onDelete: () -> Unit) {
  SheetBody(title = feature.title, subtitle = feature.summary) {
    OutlinedButton(
      onClick = onDelete,
      colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
      Text("Delete pin")
    }
  }
}
