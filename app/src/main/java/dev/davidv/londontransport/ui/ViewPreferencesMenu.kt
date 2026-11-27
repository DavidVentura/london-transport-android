package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.domain.BikeDisplay
import dev.davidv.londontransport.domain.TransportKind

@Composable
fun ViewPreferencesMenu(
  bikeDisplay: BikeDisplay, shownKinds: Set<TransportKind>,
  onBikeDisplay: (BikeDisplay) -> Unit, onToggleKind: (TransportKind) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.Tune, contentDescription = "View preferences") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      Text("View preferences", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
      Text("Docking stations", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
      listOf(BikeDisplay.ALL, BikeDisplay.FAVOURITES, BikeDisplay.NONE).forEach { display ->
        DropdownMenuItem(
          text = { Text(when (display) {
            BikeDisplay.ALL -> "All stations"
            BikeDisplay.FAVOURITES -> "Favourites only"
            BikeDisplay.NONE -> "Hidden"
          }) },
          leadingIcon = { RadioButton(selected = bikeDisplay == display, onClick = null) },
          onClick = { onBikeDisplay(display) },
        )
      }
      HorizontalDivider()
      listOf(TransportKind.BUS, TransportKind.TUBE).forEach { kind ->
        DropdownMenuItem(
          text = { Text(if (kind == TransportKind.BUS) "Show buses" else "Show Tube") },
          leadingIcon = { Checkbox(checked = kind in shownKinds, onCheckedChange = null) },
          onClick = { onToggleKind(kind) },
        )
      }
    }
  }
}
