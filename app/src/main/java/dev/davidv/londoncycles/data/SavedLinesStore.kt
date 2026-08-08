package dev.davidv.londoncycles.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.davidv.londoncycles.domain.LineId
import dev.davidv.londoncycles.domain.RouteDirection
import dev.davidv.londoncycles.domain.SavedLine
import dev.davidv.londoncycles.domain.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SavedLinesStore(private val store: DataStore<Preferences>) {
  val lines: Flow<List<SavedLine>> =
    store.data.map { preferences ->
      preferences[KEY].orEmpty().map(SavedLine::decode).sortedBy { it.id.value }
    }

  suspend fun add(line: LineId, kind: TransportKind, direction: RouteDirection) {
    edit { held ->
      held.filterNot { it.id == line } + SavedLine(line, kind, direction, visible = true)
    }
  }

  suspend fun setVisible(line: LineId, visible: Boolean) {
    edit { held -> held.map { if (it.id == line) it.copy(visible = visible) else it } }
  }

  suspend fun setDirection(line: LineId, direction: RouteDirection) {
    edit { held -> held.map { if (it.id == line) it.copy(direction = direction) else it } }
  }

  suspend fun remove(line: LineId) {
    edit { held -> held.filterNot { it.id == line } }
  }

  private suspend fun edit(change: (List<SavedLine>) -> List<SavedLine>) {
    store.edit { preferences ->
      val held = preferences[KEY].orEmpty().map(SavedLine::decode)
      preferences[KEY] = change(held).map(SavedLine::encode).toSet()
    }
  }

  private companion object {
    val KEY = stringSetPreferencesKey("saved_lines")
  }
}
