package dev.davidv.londontransport.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.MapFeature

@Composable
fun FavouritesTab(
  state: CyclesState,
  onOpen: (MapFeature) -> Unit,
  onUnfavourite: (FeatureKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val favourites = state.favouriteFeatures
  if (favourites.isEmpty()) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
      Text(
        if (state.favourites.isEmpty()) {
          "Tap a docking station on the map and star it to see its status here."
        } else {
          "Waiting for the first update."
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
      )
    }
    return
  }

  LazyColumn(modifier = modifier.fillMaxSize()) {
    items(favourites, key = { it.key.encode() }) { feature ->
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clickable { onOpen(feature) }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AvailabilityDot(feature)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
          Text(feature.title, style = MaterialTheme.typography.titleMedium)
          Text(
            feature.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = { onUnfavourite(feature.key) }) {
          Icon(Icons.Filled.Star, contentDescription = "Remove from favourites")
        }
      }
      HorizontalDivider()
    }
  }
}

@Composable
private fun AvailabilityDot(feature: MapFeature) {
  Surface(
    color = availabilityColour(feature.level),
    contentColor = Color.White,
    modifier = Modifier.size(40.dp).clip(CircleShape),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        feature.badge,
        style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}
