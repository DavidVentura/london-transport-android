package dev.davidv.londontransport.data

import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.Station
import dev.davidv.londontransport.domain.StationId
import dev.davidv.londontransport.domain.StationStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CachedStation(val id: String, val name: String, val lat: Double, val lon: Double)

sealed interface CacheRead {
  data class Hit(val stations: List<Station>) : CacheRead

  data object Empty : CacheRead

  data class Unreadable(val reason: String) : CacheRead
}

/**
 * Keeps where the stations are, never how full they were. A count that survived a reboot would be a
 * lie by the time anyone read it, so [StationStatus.Unknown] is all that comes back from here.
 */
class StationCache(private val file: File) {
  private val json = Json

  suspend fun read(): CacheRead =
    withContext(Dispatchers.IO) {
      if (!file.exists()) return@withContext CacheRead.Empty
      try {
        val cached = json.decodeFromString<List<CachedStation>>(file.readText())
        CacheRead.Hit(
          cached.map {
            Station(
              id = StationId(it.id),
              name = it.name,
              position = LatLon(it.lat, it.lon),
              status = StationStatus.Unknown,
            )
          }
        )
      } catch (e: SerializationException) {
        CacheRead.Unreadable(e.message ?: "corrupt cache")
      } catch (e: IOException) {
        CacheRead.Unreadable(e.message ?: "unreadable cache")
      }
    }

  suspend fun write(stations: List<Station>) {
    withContext(Dispatchers.IO) {
      val encoded =
        json.encodeToString(
          stations.map {
            CachedStation(it.id.value, it.name, it.position.latitude, it.position.longitude)
          }
        )
      // Written beside the real file and renamed, so a kill mid-write cannot leave a half file.
      val scratch = File(file.parentFile, "${file.name}.writing")
      scratch.writeText(encoded)
      scratch.renameTo(file)
    }
  }
}
