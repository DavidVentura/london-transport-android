package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.RouteDirection
import dev.davidv.londontransport.domain.TransportKind

private val BUS_PILL = 52.dp
private val TUBE_PILL = 104.dp

@Composable
fun LinesTab(
  state: RoutesState,
  onDirection: (LineId, RouteDirection) -> Unit,
  onVisibility: (LineId, Boolean) -> Unit,
  onForget: (LineId) -> Unit,
  onAdd: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    Column(modifier = Modifier.fillMaxSize()) {
      state.error?.let { message ->
        Text(
          message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }

      if (state.routes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            "Add a bus route or tube line to draw it on the map.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
          )
        }
        return@Column
      }

      LazyColumn {
        items(state.routes, key = { it.line.value }) { route ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            LinePill(route)
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
              Text(describe(route), style = MaterialTheme.typography.bodyLarge)
              if (route.moved) {
                Text(
                  "Route changed since you last checked",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
            // A tube line runs the same rails whichever way you board, so there is nothing to flip.
            if (route.saved.kind == TransportKind.TUBE) {
              Box(modifier = Modifier.size(48.dp))
            } else {
              IconButton(onClick = { onDirection(route.line, opposite(route.saved.direction)) }) {
                Icon(Icons.Filled.SwapVert, contentDescription = "Switch direction")
              }
            }
            IconButton(onClick = { onVisibility(route.line, !route.saved.visible) }) {
              Icon(
                if (route.saved.visible) Icons.Outlined.Visibility
                else Icons.Outlined.VisibilityOff,
                contentDescription = if (route.saved.visible) "Hide on map" else "Show on map",
              )
            }
            IconButton(onClick = { onForget(route.line) }) {
              Icon(Icons.Outlined.Delete, contentDescription = "Remove line")
            }
          }
          HorizontalDivider()
        }
      }
    }

    ExtendedFloatingActionButton(
      onClick = onAdd,
      icon = { Icon(Icons.Filled.Add, contentDescription = null) },
      text = { Text("Add line") },
      modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    )
  }
}

@Composable
private fun LinePill(route: SavedRoute) {
  Surface(
    color =
      if (route.saved.visible) routeColour(route.line, route.saved.kind)
      else MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(6.dp),
    modifier =
      Modifier.width(if (route.saved.kind == TransportKind.TUBE) TUBE_PILL else BUS_PILL)
        .height(34.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      BasicText(
        text = lineLabel(route.line, route.saved.kind),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 16.sp),
        style =
          MaterialTheme.typography.titleMedium.copy(
            color =
              if (route.saved.visible) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          ),
        modifier = Modifier.padding(horizontal = 6.dp),
      )
    }
  }
}

private fun describe(route: SavedRoute): String {
  val path = route.path ?: return route.unavailable ?: "Loading…"
  // Every tube stop is on the line whichever way you go, so a destination says nothing.
  if (route.saved.kind != TransportKind.TUBE) return "towards ${path.towards}"
  return if (path.stops.size == 1) "1 station" else "${path.stops.size} stations"
}

private fun opposite(direction: RouteDirection): RouteDirection =
  when (direction) {
    RouteDirection.INBOUND -> RouteDirection.OUTBOUND
    RouteDirection.OUTBOUND -> RouteDirection.INBOUND
  }
