package dev.davidv.londoncycles.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.davidv.londoncycles.domain.BusLine
import dev.davidv.londoncycles.domain.LineId

@Composable
fun AddLineSheet(state: RoutesState, saved: Set<LineId>, onPick: (LineId) -> Unit) {
  var query by remember { mutableStateOf("") }
  val matches =
    remember(query, state.catalogue) {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) state.catalogue
      else state.catalogue.filter { it.name.startsWith(trimmed, ignoreCase = true) }
    }

  OutlinedTextField(
    value = query,
    onValueChange = { query = it },
    singleLine = true,
    label = { Text("Filter by route number") },
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
      LineRow(line = line, alreadySaved = line.id in saved, onPick = onPick)
      HorizontalDivider()
    }
  }
}

@Composable
private fun LineRow(line: BusLine, alreadySaved: Boolean, onPick: (LineId) -> Unit) {
  Text(
    text = if (alreadySaved) "${line.name}  · already added" else line.name,
    style = MaterialTheme.typography.titleMedium,
    color =
      if (alreadySaved) MaterialTheme.colorScheme.onSurfaceVariant
      else MaterialTheme.colorScheme.onSurface,
    modifier =
      Modifier.fillMaxWidth()
        .clickable(enabled = !alreadySaved) { onPick(line.id) }
        .padding(horizontal = 24.dp, vertical = 14.dp),
  )
}
