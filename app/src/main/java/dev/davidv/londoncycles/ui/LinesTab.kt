package dev.davidv.londoncycles.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.davidv.londoncycles.domain.LineId
import dev.davidv.londoncycles.domain.RouteDirection

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
            "Add a bus route to draw it on the map.",
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
            Surface(
              color =
                if (route.saved.visible) routeColour(route.line)
                else MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier.size(width = 52.dp, height = 34.dp),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Text(
                  route.line.value,
                  style = MaterialTheme.typography.titleMedium,
                  color =
                    if (route.saved.visible) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
              Text(
                route.path?.let { "towards ${it.towards}" } ?: route.unavailable ?: "Loading…",
                style = MaterialTheme.typography.bodyLarge,
              )
              if (route.moved) {
                Text(
                  "Route changed since you last checked",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
            IconButton(onClick = { onVisibility(route.line, !route.saved.visible) }) {
              Icon(
                if (route.saved.visible) Icons.Outlined.Visibility
                else Icons.Outlined.VisibilityOff,
                contentDescription = if (route.saved.visible) "Hide on map" else "Show on map",
              )
            }
            IconButton(onClick = { onDirection(route.line, opposite(route.saved.direction)) }) {
              Icon(Icons.Filled.SwapVert, contentDescription = "Switch direction")
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

private fun opposite(direction: RouteDirection): RouteDirection =
  when (direction) {
    RouteDirection.INBOUND -> RouteDirection.OUTBOUND
    RouteDirection.OUTBOUND -> RouteDirection.INBOUND
  }
