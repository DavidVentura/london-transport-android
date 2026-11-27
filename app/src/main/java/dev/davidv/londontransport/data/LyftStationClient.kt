package dev.davidv.londontransport.data

import dev.davidv.londontransport.domain.BikeCounts
import dev.davidv.londontransport.domain.LatLon
import dev.davidv.londontransport.domain.Station
import dev.davidv.londontransport.domain.StationId
import dev.davidv.londontransport.domain.StationState
import dev.davidv.londontransport.domain.StationStatus
import dev.davidv.londontransport.santander.POLARIS_USER_AGENT
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class MalformedStation(val id: String, val reason: String)

data class ParsedFeed(val stations: List<Station>, val malformed: List<MalformedStation>)

sealed interface FeedResult {
  data class Success(val feed: ParsedFeed) : FeedResult
  data class Unreachable(val reason: String) : FeedResult
  data class Unusable(val reason: String) : FeedResult
}

fun interface StationFeed {
  suspend fun fetch(): FeedResult
}

const val LYFT_INVENTORY_ENDPOINT = "https://london.lyftbikes.com/v1/lbsbff/map/inventory"

class LyftStationClient(private val http: OkHttpClient, private val endpoint: String) : StationFeed {
  override suspend fun fetch(): FeedResult = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(endpoint)
      .header("Accept", "application/json")
      .header("User-Agent", POLARIS_USER_AGENT)
      .post("{}".toRequestBody(
        "application/json;messageType=pb.api.endpoints.v1.lbs_bff.ReadMapInventoryRequest".toMediaType()
      ))
      .build()
    try {
      http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          return@withContext FeedResult.Unreachable("Santander Cycles returned HTTP ${response.code}")
        }
        FeedResult.Success(parseLyftInventory(response.body.string()))
      }
    } catch (e: IOException) {
      FeedResult.Unreachable(e.message ?: "network unavailable")
    } catch (e: SerializationException) {
      FeedResult.Unusable(e.message ?: "unrecognised station inventory")
    }
  }
}

@Serializable
private data class InventoryResponse(@SerialName("map_inventory_json") val inventory: String)

@Serializable
private data class Inventory(val type: String, val features: List<InventoryFeature>)

@Serializable
private data class InventoryFeature(
  val type: String,
  val geometry: InventoryGeometry,
  val properties: InventoryProperties,
)

@Serializable
private data class InventoryGeometry(val type: String, val coordinates: List<Double>)

@Serializable
private data class InventoryProperties(
  @SerialName("map_item_id") val id: String,
  @SerialName("map_item_type") val type: Int,
  @SerialName("bikes_available") val standard: Int,
  @SerialName("ebikes_available") val electric: Int,
  @SerialName("docks_available") val docks: Int,
  @SerialName("is_offline") val offline: Boolean,
  @SerialName("search_result_representation") val representation: StationRepresentation,
)

@Serializable
private data class StationRepresentation(val title: String)

private val inventoryJson = Json { ignoreUnknownKeys = true }

fun parseLyftInventory(body: String): ParsedFeed {
  val response = inventoryJson.decodeFromString<InventoryResponse>(body)
  val inventory = inventoryJson.decodeFromString<Inventory>(response.inventory)
  if (inventory.type != "FeatureCollection") {
    throw SerializationException("expected station FeatureCollection")
  }
  val docks = inventory.features.filter { it.type == "Feature" && it.properties.type == 1 }
  val (valid, invalid) = docks.partition { feature ->
    val props = feature.properties
    val geometry = feature.geometry
    props.id.isNotBlank() && props.representation.title.isNotBlank() && geometry.type == "Point" &&
      geometry.coordinates.size == 2 && geometry.coordinates[0].isFinite() &&
      geometry.coordinates[1].isFinite() && geometry.coordinates[0] in -180.0..180.0 &&
      geometry.coordinates[1] in -90.0..90.0 && props.standard >= 0 &&
      props.electric >= 0 && props.docks >= 0
  }
  return ParsedFeed(
    valid.map { feature ->
      val props = feature.properties
      Station(
        StationId("BikePoints_${props.id}"),
        tidyName(props.representation.title),
        LatLon(feature.geometry.coordinates[1], feature.geometry.coordinates[0]),
        StationStatus.Live(
          BikeCounts(props.standard, props.electric),
          props.docks,
          if (props.offline) StationState.LOCKED else StationState.OPERATIONAL,
        ),
      )
    },
    invalid.map { MalformedStation(it.properties.id, "invalid station inventory record") },
  )
}

/** TfL names carry stray spacing, e.g. "River Street , Clerkenwell". */
internal fun tidyName(raw: String): String =
  raw.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.])"), "$1").trim()
