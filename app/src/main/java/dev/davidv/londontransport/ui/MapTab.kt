package dev.davidv.londontransport.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.MapCamera
import dev.davidv.londontransport.domain.MapFeature
import dev.davidv.londontransport.map.DrawnRoute
import dev.davidv.londontransport.map.FeatureMap

@Composable
fun MapTab(
  state: CyclesState,
  features: List<MapFeature>,
  routes: List<DrawnRoute>,
  initialCamera: MapCamera,
  onSelect: (MapFeature?) -> Unit,
  onCameraMoved: (MapCamera) -> Unit,
  onPlacePin: (LatLon) -> Unit,
  onFocusHandled: () -> Unit,
  onFitApplied: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var followLocation by rememberSaveable { mutableStateOf(false) }
  var permitted by rememberSaveable { mutableStateOf(hasLocationPermission(context)) }
  val requestPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      permitted = granted
      followLocation = granted
    }

  Box(modifier = modifier) {
    FeatureMap(
      features = features,
      selected = state.selected,
      favourites = state.favourites,
      routes = routes,
      initialCamera = initialCamera,
      focus = state.focus,
      fit = state.fitBounds,
      darkTiles = isSystemInDarkTheme(),
      followLocation = followLocation,
      onSelect = onSelect,
      onCameraMoved = onCameraMoved,
      onPlacePin = onPlacePin,
      onFocusHandled = onFocusHandled,
      onFitApplied = onFitApplied,
      modifier = Modifier.fillMaxSize(),
    )

    state.error?.let { message ->
      Card(
        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
          ),
      ) {
        Row(
          modifier = Modifier.padding(start = 16.dp, end = 8.dp).fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
          TextButton(onClick = onRetry) { Text("Retry") }
        }
      }
    }

    FloatingActionButton(
      onClick = {
        if (permitted) {
          followLocation = !followLocation
        } else {
          requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
      },
      modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
      Icon(
        if (followLocation) Icons.Filled.MyLocation else Icons.Outlined.LocationSearching,
        contentDescription = "Follow my location",
      )
    }
  }
}

private fun hasLocationPermission(context: Context): Boolean =
  ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
    PackageManager.PERMISSION_GRANTED
