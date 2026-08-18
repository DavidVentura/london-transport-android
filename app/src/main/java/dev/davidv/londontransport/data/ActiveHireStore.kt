package dev.davidv.londontransport.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.davidv.londontransport.domain.ActiveHire
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.ReleaseCode
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Survives the app being killed on the walk from phone to dock. */
class ActiveHireStore(private val store: DataStore<Preferences>) {
  val hire: Flow<ActiveHire?> =
    store.data.map { preferences ->
      val station = preferences[STATION]
      val name = preferences[NAME]
      val code = preferences[CODE]
      val expires = preferences[EXPIRES]
      if (station == null || name == null || code == null || expires == null) null
      else
        ActiveHire(
          station = FeatureKey.decode(station),
          stationName = name,
          code = ReleaseCode(code),
          expiresAt = Instant.ofEpochMilli(expires),
        )
    }

  suspend fun save(hire: ActiveHire) {
    store.edit { preferences ->
      preferences[STATION] = hire.station.encode()
      preferences[NAME] = hire.stationName
      preferences[CODE] = hire.code.value
      preferences[EXPIRES] = hire.expiresAt.toEpochMilli()
    }
  }

  suspend fun clear() {
    store.edit { preferences -> preferences.clear() }
  }

  private companion object {
    val STATION = stringPreferencesKey("station")
    val NAME = stringPreferencesKey("station_name")
    val CODE = stringPreferencesKey("release_code")
    val EXPIRES = longPreferencesKey("expires_at")
  }
}
