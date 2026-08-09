package dev.davidv.londontransport.data

import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.LineId
import dev.davidv.londontransport.domain.RouteDirection
import dev.davidv.londontransport.domain.RoutePath
import dev.davidv.londontransport.domain.RouteStop
import dev.davidv.londontransport.domain.StopId
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CachedRoute(
  val line: String,
  val direction: String,
  val towards: String,
  // Absent in caches written before routes had an age; those read as long stale, so they refetch.
  val fetchedAt: Long = 0,
  val segments: List<List<List<Double>>>,
  // Absent in caches written before stops were kept; those fill in on the next fetch.
  val stops: List<CachedStop> = emptyList(),
)

@Serializable
private data class CachedStop(
  val id: String,
  val name: String,
  val lat: Double,
  val lon: Double,
  val letter: String? = null,
)

/** A route as last seen, with when that was — a line only needs asking about a few times a day. */
data class HeldRoute(val path: RoutePath, val fetchedAt: Instant)

sealed interface RouteCacheRead {
  data class Held(val routes: List<HeldRoute>) : RouteCacheRead

  data object Empty : RouteCacheRead

  data class Unreadable(val reason: String) : RouteCacheRead
}

/** What writing a route did to the copy already on disk. */
enum class RouteChange {
  FIRST_SEEN,
  UNCHANGED,
  MOVED,
}

/**
 * Holds every saved route so the map draws without the network, and — because TfL edits diversions
 * straight into the published route with no flag on them — so a route that moves can be noticed.
 */
class RouteCache(private val file: File) {
  private val json = Json

  suspend fun read(): RouteCacheRead = withContext(Dispatchers.IO) { readBlocking() }

  suspend fun put(path: RoutePath, at: Instant): RouteChange =
    withContext(Dispatchers.IO) {
      val held = (readBlocking() as? RouteCacheRead.Held)?.routes.orEmpty()
      val previous =
        held.firstOrNull { it.path.line == path.line && it.path.direction == path.direction }
      write(
        held.filterNot { it.path.line == path.line && it.path.direction == path.direction } +
          HeldRoute(path, at)
      )

      when {
        previous == null -> RouteChange.FIRST_SEEN
        previous.path.segments == path.segments -> RouteChange.UNCHANGED
        else -> RouteChange.MOVED
      }
    }

  suspend fun forget(line: LineId) {
    withContext(Dispatchers.IO) {
      val held = (readBlocking() as? RouteCacheRead.Held)?.routes.orEmpty()
      write(held.filterNot { it.path.line == line })
    }
  }

  private fun readBlocking(): RouteCacheRead {
    if (!file.exists()) return RouteCacheRead.Empty
    return try {
      RouteCacheRead.Held(json.decodeFromString<List<CachedRoute>>(file.readText()).map(::toHeld))
    } catch (e: SerializationException) {
      RouteCacheRead.Unreadable(e.message ?: "corrupt route cache")
    } catch (e: IllegalArgumentException) {
      RouteCacheRead.Unreadable(e.message ?: "unknown direction in route cache")
    } catch (e: IOException) {
      RouteCacheRead.Unreadable(e.message ?: "unreadable route cache")
    }
  }

  private fun write(routes: List<HeldRoute>) {
    val scratch = File(file.parentFile, "${file.name}.writing")
    scratch.writeText(json.encodeToString(routes.map(::toCached)))
    scratch.renameTo(file)
  }

  private fun toHeld(cached: CachedRoute) =
    HeldRoute(path = toPath(cached), fetchedAt = Instant.ofEpochMilli(cached.fetchedAt))

  private fun toPath(cached: CachedRoute) =
    RoutePath(
      line = LineId(cached.line),
      direction = RouteDirection.valueOf(cached.direction),
      towards = cached.towards,
      stops =
        cached.stops.map {
          RouteStop(
            id = StopId(it.id),
            name = it.name,
            position = LatLon(it.lat, it.lon),
            letter = it.letter,
          )
        },
      segments =
        cached.segments.map { segment ->
          segment.map {
            if (it.size < 2) throw SerializationException("coordinate with ${it.size} values")
            LatLon(latitude = it[0], longitude = it[1])
          }
        },
    )

  private fun toCached(held: HeldRoute): CachedRoute {
    val path = held.path
    return CachedRoute(
      line = path.line.value,
      direction = path.direction.name,
      towards = path.towards,
      fetchedAt = held.fetchedAt.toEpochMilli(),
      segments = path.segments.map { segment -> segment.map { listOf(it.latitude, it.longitude) } },
      stops =
        path.stops.map {
          CachedStop(
            id = it.id.value,
            name = it.name,
            lat = it.position.latitude,
            lon = it.position.longitude,
            letter = it.letter,
          )
        },
    )
  }
}
