package dev.davidv.londontransport.layers

import android.util.Log
import dev.davidv.londontransport.data.CacheRead
import dev.davidv.londontransport.data.FeedResult
import dev.davidv.londontransport.data.StationCache
import dev.davidv.londontransport.data.TflBikePointClient
import dev.davidv.londontransport.domain.LayerId
import dev.davidv.londontransport.domain.MapFeature

class CycleHireLayer(private val client: TflBikePointClient, private val cache: StationCache) :
  MapLayer {
  override val id: LayerId = CYCLE_HIRE_LAYER
  override val title: String = "Santander Cycles"

  override suspend fun cached(): List<MapFeature> =
    when (val read = cache.read()) {
      is CacheRead.Hit -> read.stations.map(::stationFeature)
      CacheRead.Empty -> emptyList()
      is CacheRead.Unreadable -> {
        Log.w(TAG, "discarding station cache: ${read.reason}")
        emptyList()
      }
    }

  override suspend fun load(): LayerLoad =
    when (val result = client.fetch()) {
      is FeedResult.Unreachable -> offline("Could not reach TfL: ${result.reason}")
      is FeedResult.Unusable -> offline("TfL feed unreadable: ${result.reason}")
      is FeedResult.Success -> {
        if (result.feed.malformed.isNotEmpty()) {
          Log.w(TAG, "dropped ${result.feed.malformed.size} malformed docking stations")
        }
        cache.write(result.feed.stations)
        LayerLoad.Loaded(result.feed.stations.map(::stationFeature))
      }
    }

  private suspend fun offline(message: String): LayerLoad {
    val onDisk = cached()
    return if (onDisk.isEmpty()) LayerLoad.Failed(message)
    else LayerLoad.Offline(onDisk, message)
  }

  private companion object {
    const val TAG = "CycleHireLayer"
  }
}
