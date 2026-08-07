package dev.davidv.londoncycles.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.davidv.londoncycles.domain.LatLon
import dev.davidv.londoncycles.domain.Pin
import dev.davidv.londoncycles.domain.PinId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PinRecord(val id: String, val name: String, val lat: Double, val lon: Double)

private val json = Json

class PinsStore(private val store: DataStore<Preferences>) {
  val pins: Flow<List<Pin>> = store.data.map { preferences -> decode(preferences[KEY]) }

  suspend fun add(name: String, position: LatLon) {
    val pin =
      PinRecord(
        id = UUID.randomUUID().toString(),
        name = name,
        lat = position.latitude,
        lon = position.longitude,
      )
    store.edit { preferences -> preferences[KEY] = encode(decode(preferences[KEY]) + pin.toPin()) }
  }

  suspend fun remove(id: PinId) {
    store.edit { preferences ->
      preferences[KEY] = encode(decode(preferences[KEY]).filterNot { it.id == id })
    }
  }

  private fun decode(stored: String?): List<Pin> =
    if (stored == null) emptyList()
    else json.decodeFromString<List<PinRecord>>(stored).map(PinRecord::toPin)

  private fun encode(pins: List<Pin>): String =
    json.encodeToString(
      pins.map { PinRecord(it.id.value, it.name, it.position.latitude, it.position.longitude) }
    )

  private companion object {
    val KEY = stringPreferencesKey("pins")
  }
}

private fun PinRecord.toPin() = Pin(PinId(id), name, LatLon(lat, lon))
