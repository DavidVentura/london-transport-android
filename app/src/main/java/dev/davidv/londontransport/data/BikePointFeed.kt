package dev.davidv.londontransport.data

import dev.davidv.londontransport.domain.BikeCounts
import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.Station
import dev.davidv.londontransport.domain.StationId
import dev.davidv.londontransport.domain.StationState
import dev.davidv.londontransport.domain.StationStatus
import dev.davidv.londontransport.domain.TerminalName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BikePointDto(
  val id: String,
  val commonName: String,
  val lat: Double,
  val lon: Double,
  val additionalProperties: List<AdditionalPropertyDto>,
)

@Serializable
private data class AdditionalPropertyDto(val key: String, val value: String)

data class MalformedStation(val id: String, val reason: String)

data class ParsedFeed(val stations: List<Station>, val malformed: List<MalformedStation>)

private val json = Json { ignoreUnknownKeys = true }

fun parseBikePointFeed(body: String): ParsedFeed {
  val (parsed, malformed) =
    json
      .decodeFromString<List<BikePointDto>>(body)
      .map(::toStation)
      .partition { it is StationParse.Parsed }

  return ParsedFeed(
    stations = parsed.map { (it as StationParse.Parsed).station },
    malformed = malformed.map { (it as StationParse.Malformed).error },
  )
}

private sealed interface StationParse {
  data class Parsed(val station: Station) : StationParse

  data class Malformed(val error: MalformedStation) : StationParse
}

private fun toStation(dto: BikePointDto): StationParse {
  val properties = dto.additionalProperties.associate { it.key to it.value }

  fun count(key: String): Int? = properties[key]?.toIntOrNull()

  val terminal = properties["TerminalName"]
  if (terminal == null) {
    return StationParse.Malformed(MalformedStation(dto.id, "no TerminalName"))
  }

  val standard = count("NbStandardBikes")
  val electric = count("NbEBikes")
  val emptyDocks = count("NbEmptyDocks")
  val capacity = count("NbDocks")
  if (standard == null || electric == null || emptyDocks == null || capacity == null) {
    return StationParse.Malformed(MalformedStation(dto.id, "missing or non-numeric dock counts"))
  }

  val installed = properties["Installed"]?.toBooleanStrictOrNull()
  val locked = properties["Locked"]?.toBooleanStrictOrNull()
  if (installed == null || locked == null) {
    return StationParse.Malformed(MalformedStation(dto.id, "missing Installed/Locked flags"))
  }

  return StationParse.Parsed(
    Station(
      id = StationId(dto.id),
      terminal = TerminalName(terminal),
      name = tidyName(dto.commonName),
      position = LatLon(dto.lat, dto.lon),
      status =
        StationStatus.Live(
          bikes = BikeCounts(standard = standard, electric = electric),
          emptyDocks = emptyDocks,
          capacity = capacity,
          state =
            when {
              !installed -> StationState.NOT_INSTALLED
              locked -> StationState.LOCKED
              else -> StationState.OPERATIONAL
            },
        ),
    )
  )
}

/** TfL names carry stray spacing, e.g. "River Street , Clerkenwell". */
internal fun tidyName(raw: String): String =
  raw.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.])"), "$1").trim()
