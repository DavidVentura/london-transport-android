package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
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
  Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
    Text(feature.title, style = MaterialTheme.typography.headlineSmall)
    Text(
      feature.summary,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
    )

    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
      Icon(
        Icons.Outlined.Delete,
        contentDescription = null,
        modifier = Modifier.padding(end = 8.dp),
      )
      Text("Delete pin")
    }
  }
}
