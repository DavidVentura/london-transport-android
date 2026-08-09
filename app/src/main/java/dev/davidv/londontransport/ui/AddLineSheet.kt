package dev.davidv.londontransport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.TransitLine
import dev.davidv.londontransport.domain.TransportKind

@Composable
fun AddLineSheet(state: RoutesState, saved: Set<LineId>, onPick: (TransitLine) -> Unit) {
  var query by remember { mutableStateOf("") }
  val matches =
    remember(query, state.catalogue, saved) {
      val trimmed = query.trim()
      state.catalogue
        .filterNot { it.id in saved }
        .filter {
          trimmed.isEmpty() ||
            it.name.startsWith(trimmed, ignoreCase = true) ||
            (it.kind == TransportKind.TUBE && it.name.contains(trimmed, ignoreCase = true))
        }
    }

  OutlinedTextField(
    value = query,
    onValueChange = { query = it },
    singleLine = true,
    label = { Text("Filter by route or line") },
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
  )

  if (state.loadingCatalogue) {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp).padding(top = 8.dp)) {
    items(matches, key = { it.id.value }) { line ->
      LineRow(line = line, onPick = onPick)
      HorizontalDivider()
    }
  }
}

@Composable
private fun LineRow(line: TransitLine, onPick: (TransitLine) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clickable { onPick(line) }
        .padding(horizontal = 24.dp, vertical = 14.dp),
  ) {
    Box(
      modifier =
        Modifier.size(width = 20.dp, height = 20.dp)
          .clip(CircleShape)
          .background(routeColour(line.id, line.kind))
    )
    Text(
      text = line.name,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(start = 16.dp),
    )
  }
}
