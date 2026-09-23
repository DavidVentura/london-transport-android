package dev.davidv.londontransport.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.davidv.londontransport.domain.BikeDisplay
import dev.davidv.londontransport.domain.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsStore(private val store: DataStore<Preferences>) {
  val bikeDisplay: Flow<BikeDisplay> =
    store.data.map { preferences ->
      preferences[BIKE_DISPLAY]?.let(BikeDisplay::valueOf)
      // Before docking stations had three settings they had two, spread over two keys.
      ?: when {
          BIKE in preferences[HIDDEN_KINDS].orEmpty() -> BikeDisplay.NONE
          preferences[FAVOURITES_ONLY] == true -> BikeDisplay.FAVOURITES
          else -> BikeDisplay.ALL
        }
    }

  suspend fun setBikeDisplay(display: BikeDisplay) {
    store.edit { preferences -> preferences[BIKE_DISPLAY] = display.name }
  }

  val hiddenKinds: Flow<Set<TransportKind>> =
    store.data.map { preferences ->
      preferences[HIDDEN_KINDS].orEmpty().map(TransportKind::valueOf).toSet()
    }

  suspend fun setKindVisible(kind: TransportKind, visible: Boolean) {
    store.edit { preferences ->
      val hidden = preferences[HIDDEN_KINDS].orEmpty()
      preferences[HIDDEN_KINDS] = if (visible) hidden - kind.name else hidden + kind.name
    }
  }

  private companion object {
    const val BIKE = "BIKE"
    val FAVOURITES_ONLY = booleanPreferencesKey("favourites_only")
    val BIKE_DISPLAY = stringPreferencesKey("bike_display")
    val HIDDEN_KINDS = stringSetPreferencesKey("hidden_kinds")
  }
}
