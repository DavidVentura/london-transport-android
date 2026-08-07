package dev.davidv.londoncycles.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.davidv.londoncycles.domain.MapFeature
import dev.davidv.londoncycles.layers.BUS_STOP_LAYER
import dev.davidv.londoncycles.layers.PIN_LAYER
import dev.davidv.londoncycles.map.DrawnRoute
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val STALE_AFTER: Duration = Duration.ofSeconds(60)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private enum class Tab(val label: String, val icon: ImageVector) {
  MAP("Map", Icons.Outlined.Map),
  FAVOURITES("Favourites", Icons.Filled.Star),
  LINES("Lines", Icons.Outlined.DirectionsBus),
}

private fun subtitle(state: CyclesState): String? =
  when {
    state.offline -> "Offline · saved positions, no counts"
    state.fetchedAt != null ->
      "Updated ${CLOCK.format(state.fetchedAt.atZone(ZoneId.systemDefault()))}"
    else -> null
  }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CyclesApp(viewModel: CyclesViewModel, routesViewModel: RoutesViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val routes by routesViewModel.state.collectAsStateWithLifecycle()
  var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
  var picking by rememberSaveable { mutableStateOf(false) }

  val drawn =
    routes.drawable.map { path -> DrawnRoute(path, routeColour(path.line).toArgb()) }
  val features = state.visibleFeatures + routes.stopFeatures
  val selected = features.firstOrNull { it.key == state.selected }

  LifecycleResumeEffect(Unit) {
    viewModel.refreshIfOlderThan(STALE_AFTER)
    onPauseOrDispose {}
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(state.layerTitle)
            subtitle(state)?.let { line ->
              Text(line, style = MaterialTheme.typography.labelSmall)
            }
          }
        },
        actions = {
          IconButton(onClick = viewModel::toggleFavouritesOnly) {
            Icon(
              if (state.favouritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
              contentDescription = "Show favourites only",
              tint =
                if (state.favouritesOnly) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (state.refreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
          } else {
            IconButton(onClick = viewModel::refresh) {
              Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
          }
        },
      )
    },
    bottomBar = {
      NavigationBar {
        Tab.entries.forEach { entry ->
          NavigationBarItem(
            selected = tab == entry,
            onClick = { tab = entry },
            icon = { Icon(entry.icon, contentDescription = null) },
            label = { Text(entry.label) },
          )
        }
      }
    },
  ) { padding ->
    when (tab) {
      Tab.MAP ->
        MapTab(
          state = state,
          features = features,
          routes = drawn,
          initialCamera = viewModel.camera,
          onSelect = { feature -> viewModel.select(feature?.key) },
          onCameraMoved = { camera -> viewModel.camera = camera },
          onPlacePin = viewModel::startPin,
          onFocusHandled = viewModel::focusHandled,
          onFitApplied = viewModel::fitApplied,
          onRetry = viewModel::refresh,
          modifier = Modifier.fillMaxSize().padding(padding),
        )
      Tab.FAVOURITES ->
        FavouritesTab(
          state = state,
          onOpen = { feature ->
            viewModel.focusOn(feature)
            tab = Tab.MAP
          },
          onUnfavourite = viewModel::toggleFavourite,
          modifier = Modifier.fillMaxSize().padding(padding),
        )
      Tab.LINES ->
        LinesTab(
          state = routes,
          onDirection = routesViewModel::setDirection,
          onVisibility = routesViewModel::setVisible,
          onForget = routesViewModel::forget,
          onAdd = {
            routesViewModel.loadCatalogue()
            picking = true
          },
          modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
  }

  if (picking) {
    ModalBottomSheet(
      onDismissRequest = { picking = false },
      sheetState = rememberModalBottomSheetState(),
    ) {
      AddLineSheet(
        state = routes,
        saved = routes.routes.map { it.line }.toSet(),
        onPick = { line ->
          routesViewModel.save(line)
          picking = false
        },
      )
    }
  }

  if (state.pendingPin != null) {
    NamePinDialog(onConfirm = viewModel::confirmPin, onDismiss = viewModel::cancelPin)
  }

  if (selected != null) {
    ModalBottomSheet(
      onDismissRequest = { viewModel.select(null) },
      sheetState = rememberModalBottomSheetState(),
    ) {
      if (selected.key.layer == BUS_STOP_LAYER) {
        StopSheet(feature = selected)
      } else if (selected.key.layer == PIN_LAYER) {
        PinSheet(feature = selected, onDelete = { viewModel.deletePin(selected.key) })
      } else {
        FeatureSheet(
          feature = selected,
          favourite = selected.key in state.favourites,
          onToggleFavourite = { viewModel.toggleFavourite(selected.key) },
        )
      }
    }
  }
}
