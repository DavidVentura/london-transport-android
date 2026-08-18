package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.domain.HireTarget
import dev.davidv.londontransport.domain.MapFeature

@Composable
fun FeatureSheet(
  feature: MapFeature,
  favourite: Boolean,
  onToggleFavourite: () -> Unit,
  hire: HireState,
  onRent: (HireTarget) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        feature.title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = onToggleFavourite) {
        Icon(
          if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
          contentDescription =
            if (favourite) "Remove from favourites" else "Add to favourites",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
    Text(
      feature.summary,
      style = MaterialTheme.typography.bodyLarge,
      color = availabilityColour(feature.level),
      modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )

    feature.details.forEach { detail ->
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          detail.label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(detail.value, style = MaterialTheme.typography.bodyMedium)
      }
      HorizontalDivider()
    }

    if (feature.hire != null) {
      HireButton(
        target = feature.hire,
        loggedIn = hire.account != null,
        busy = hire.releasing(feature.key),
        onRent = onRent,
      )
      hire.failure(feature.key)?.let { message ->
        Text(
          message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 12.dp),
        )
      }
    }
  }
}

/** One tap releases a bike, so the label says which of the two things the tap is about to do. */
@Composable
private fun HireButton(
  target: HireTarget,
  loggedIn: Boolean,
  busy: Boolean,
  onRent: (HireTarget) -> Unit,
) {
  Button(
    onClick = { onRent(target) },
    enabled = !busy,
    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
  ) {
    if (busy) {
      CircularProgressIndicator(
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(16.dp),
      )
    }
    Text(
      when {
        busy -> "Releasing a bike…"
        loggedIn -> "Rent bike"
        else -> "Log in to rent a bike"
      },
      modifier = Modifier.padding(start = if (busy) 12.dp else 0.dp),
    )
  }
}
