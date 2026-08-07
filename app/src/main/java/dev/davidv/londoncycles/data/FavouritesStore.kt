package dev.davidv.londoncycles.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.davidv.londoncycles.domain.FeatureKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavouritesStore(private val store: DataStore<Preferences>) {
  val favourites: Flow<Set<FeatureKey>> =
    store.data.map { preferences ->
      preferences[KEY].orEmpty().map(FeatureKey::decode).toSet()
    }

  suspend fun toggle(key: FeatureKey) {
    store.edit { preferences ->
      val current = preferences[KEY].orEmpty()
      val encoded = key.encode()
      preferences[KEY] = if (encoded in current) current - encoded else current + encoded
    }
  }

  private companion object {
    val KEY = stringSetPreferencesKey("favourite_features")
  }
}
