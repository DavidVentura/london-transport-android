package dev.davidv.londontransport.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davidv.londontransport.domain.ActiveHire
import dev.davidv.londontransport.santander.LONDON_TIME
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(LONDON_TIME)
private const val TICK_MS = 10_000L

/** Recomputed on a timer, because the only thing that changes about a code is how long it has left. */
@Composable
private fun rememberRemaining(expiresAt: Instant): Duration {
  val remaining by
    produceState(Duration.between(Instant.now(), expiresAt), expiresAt) {
      while (true) {
        value = Duration.between(Instant.now(), expiresAt)
        delay(TICK_MS)
      }
    }
  return remaining
}

private fun leftToRun(remaining: Duration): String =
  when {
    remaining.isNegative || remaining.isZero -> "expired"
    remaining < Duration.ofMinutes(1) -> "under a minute left"
    else -> "${remaining.toMinutes()} min left"
  }

/** Follows the rider off the station sheet, since the code is needed at the dock, not on screen. */
@Composable
fun ReleaseCodeBar(hire: ActiveHire, onOpen: () -> Unit, onDismiss: () -> Unit) {
  val remaining = rememberRemaining(hire.expiresAt)
  Surface(
    color = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
      Text(
        hire.code.value,
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          hire.stationName,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
        )
        Text(
          "until ${UNTIL.format(hire.expiresAt)} · ${leftToRun(remaining)}",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      IconButton(onClick = onDismiss) {
        Icon(Icons.Filled.Close, contentDescription = "Dismiss release code")
      }
    }
  }
}
