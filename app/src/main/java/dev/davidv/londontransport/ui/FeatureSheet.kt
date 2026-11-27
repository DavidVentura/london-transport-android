package dev.davidv.londontransport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.domain.MapFeature
import dev.davidv.londontransport.domain.Stat

/** Bikes are hired by scanning one at the dock, so a station only shows what is there. */
@Composable
fun FeatureSheet(feature: MapFeature, favourite: Boolean, onToggleFavourite: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 12.dp, bottom = 24.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      AvailabilityDot(feature, Modifier.size(12.dp))
      Text(
        feature.title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.weight(1f).padding(start = 12.dp),
      )
      IconButton(onClick = onToggleFavourite) {
        Icon(
          if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
          contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
    if (feature.stats.isEmpty()) {
      Text(
        feature.summary,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, end = 12.dp),
      )
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp, end = 12.dp)) {
        feature.stats.forEach { StatTile(it, Modifier.weight(1f)) }
      }
    }
  }
}

@Composable
private fun StatTile(stat: Stat, modifier: Modifier) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
      Text(stat.value.toString(), style = MaterialTheme.typography.headlineMedium)
      Text(stat.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** The availability colours are for marks only; on text they fail contrast on dark surfaces. */
@Composable
fun AvailabilityDot(feature: MapFeature, modifier: Modifier) {
  Box(modifier.background(availabilityColour(feature.level), CircleShape))
}
