package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.santander.CurrentRide
import dev.davidv.londontransport.santander.formatRideDuration
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val SINCE = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"))

/** A ride without a start time has been accepted but the bike is still in the dock. */
@Composable
fun ActiveRideBar(ride: CurrentRide, onRefresh: () -> Unit, clock: Clock = Clock.systemDefaultZone()) {
  var now by remember { mutableStateOf(clock.instant()) }
  LaunchedEffect(ride.startedAt) {
    while (true) {
      now = clock.instant()
      delay(15_000)
    }
  }
  Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
      Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = null, modifier = Modifier.size(28.dp))
      Column(Modifier.weight(1f).padding(start = 16.dp)) {
        val started = ride.startedAt
        if (started == null) {
          Text("Unlocking…", style = MaterialTheme.typography.titleMedium)
          Text("Pull the bike from the dock once it releases", style = MaterialTheme.typography.bodySmall)
        } else {
          val elapsed = Duration.between(started, now).coerceAtLeast(Duration.ZERO)
          Text("Riding · ${formatRideDuration(elapsed)}", style = MaterialTheme.typography.titleMedium)
          Text("Since ${SINCE.format(started)}", style = MaterialTheme.typography.bodySmall)
        }
      }
      IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh ride") }
    }
  }
}
