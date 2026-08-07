package dev.davidv.londoncycles.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsStore(private val store: DataStore<Preferences>) {
  val favouritesOnly: Flow<Boolean> = store.data.map { it[FAVOURITES_ONLY] == true }

  suspend fun setFavouritesOnly(only: Boolean) {
    store.edit { preferences -> preferences[FAVOURITES_ONLY] = only }
  }

  private companion object {
    val FAVOURITES_ONLY = booleanPreferencesKey("favourites_only")
  }
}
