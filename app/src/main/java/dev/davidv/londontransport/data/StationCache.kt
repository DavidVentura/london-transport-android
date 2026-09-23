package dev.davidv.londontransport.data

import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.Station
import dev.davidv.londontransport.domain.StationId
import dev.davidv.londontransport.domain.StationStatus
import dev.davidv.londontransport.domain.TerminalName
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CachedStation(
  val id: String,
  val terminal: String,
  val name: String,
  val lat: Double,
  val lon: Double,
)

sealed interface CacheRead {
  data class Hit(val stations: List<Station>) : CacheRead

  data object Empty : CacheRead

  data class Unreadable(val reason: String) : CacheRead
}

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
              terminal = TerminalName(it.terminal),
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
            CachedStation(
              id = it.id.value,
              terminal = it.terminal.value,
              name = it.name,
              lat = it.position.latitude,
              lon = it.position.longitude,
            )
          }
        )
      // Written beside the real file and renamed, so a kill mid-write cannot leave a half file.
      val scratch = File(file.parentFile, "${file.name}.writing")
      scratch.writeText(encoded)
      scratch.renameTo(file)
    }
  }
}
