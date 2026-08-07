package dev.davidv.londoncycles.data

import dev.davidv.londoncycles.domain.BusLine
import dev.davidv.londoncycles.domain.LatLon
import dev.davidv.londoncycles.domain.LineId
import dev.davidv.londoncycles.domain.RouteDirection
import dev.davidv.londoncycles.domain.RoutePath
import dev.davidv.londoncycles.domain.RouteStop
import dev.davidv.londoncycles.domain.StopId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

const val TFL_API_BASE = "https://api.tfl.gov.uk"

@Serializable private data class LineDto(val id: String, val name: String)

@Serializable
private data class RouteSequenceDto(
  val lineId: String,
  val lineStrings: List<String> = emptyList(),
  val stopPointSequences: List<StopSequenceDto> = emptyList(),
)

@Serializable private data class StopSequenceDto(val stopPoint: List<SequenceStopDto> = emptyList())

@Serializable
private data class SequenceStopDto(
  val id: String,
  val name: String,
  val lat: Double,
  val lon: Double,
  val stopLetter: String? = null,
)

sealed interface LinesResult {
  data class Success(val lines: List<BusLine>) : LinesResult

  data class Unreachable(val reason: String) : LinesResult

  data class Unusable(val reason: String) : LinesResult
}

sealed interface RouteResult {
  data class Success(val path: RoutePath) : RouteResult

  /** The line exists but does not run this way — plenty of routes are one-directional. */
  data object NotServed : RouteResult

  data class Unreachable(val reason: String) : RouteResult

  data class Unusable(val reason: String) : RouteResult
}

class TflLineClient(private val http: OkHttpClient, private val apiBase: String) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun busLines(): LinesResult {
    val body =
      when (val fetched = get("$apiBase/Line/Mode/bus/Route")) {
        is Fetched.Failed -> return LinesResult.Unreachable(fetched.reason)
        is Fetched.Body -> fetched.text
      }

    return try {
      LinesResult.Success(
        json.decodeFromString<List<LineDto>>(body).map { BusLine(LineId(it.id), it.name) }
      )
    } catch (e: SerializationException) {
      LinesResult.Unusable(e.message ?: "unrecognised line list")
    }
  }

  suspend fun route(line: LineId, direction: RouteDirection): RouteResult {
    val leg = if (direction == RouteDirection.INBOUND) "inbound" else "outbound"
    val body =
      when (val fetched = get("$apiBase/Line/${line.value}/Route/Sequence/$leg")) {
        is Fetched.Failed -> return RouteResult.Unreachable(fetched.reason)
        is Fetched.Body -> fetched.text
      }

    val sequence =
      try {
        json.decodeFromString<RouteSequenceDto>(body)
      } catch (e: SerializationException) {
        return RouteResult.Unusable(e.message ?: "unrecognised route")
      }

    val stops =
      sequence.stopPointSequences.flatMap { branch ->
        branch.stopPoint.map {
          RouteStop(
            id = StopId(it.id),
            name = it.name,
            position = LatLon(latitude = it.lat, longitude = it.lon),
            letter = it.stopLetter,
          )
        }
      }
    val towards = stops.lastOrNull()?.name
    val segments =
      try {
        sequence.lineStrings.flatMap(::decodeLineString)
      } catch (e: SerializationException) {
        return RouteResult.Unusable(e.message ?: "unrecognised route geometry")
      }

    if (towards == null || segments.isEmpty()) return RouteResult.NotServed

    return RouteResult.Success(RoutePath(line, direction, towards, segments, stops))
  }

  private sealed interface Fetched {
    data class Body(val text: String) : Fetched

    data class Failed(val reason: String) : Fetched
  }

  private suspend fun get(url: String): Fetched =
    withContext(Dispatchers.IO) {
      try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
          if (response.isSuccessful) Fetched.Body(response.body.string())
          else Fetched.Failed("TfL returned HTTP ${response.code}")
        }
      } catch (e: IOException) {
        Fetched.Failed(e.message ?: "network unavailable")
      }
    }

  /** Each entry is itself a JSON document: an array of segments of `[longitude, latitude]`. */
  private fun decodeLineString(encoded: String): List<List<LatLon>> =
    json
      .decodeFromString<List<List<List<Double>>>>(encoded)
      .map { segment ->
        segment.map { pair ->
          if (pair.size < 2) throw SerializationException("coordinate with ${pair.size} values")
          LatLon(latitude = pair[1], longitude = pair[0])
        }
      }
      .filter { it.size >= 2 }
}
