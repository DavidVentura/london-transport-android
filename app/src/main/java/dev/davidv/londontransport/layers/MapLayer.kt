package dev.davidv.londontransport.layers

import dev.davidv.londontransport.domain.LayerId
import dev.davidv.londontransport.domain.MapFeature

sealed interface LayerLoad {
  data class Loaded(val features: List<MapFeature>) : LayerLoad

  data class Offline(val features: List<MapFeature>, val message: String) : LayerLoad

  data class Failed(val message: String) : LayerLoad
}

interface MapLayer {
  val id: LayerId
  val title: String

  suspend fun cached(): List<MapFeature>

  suspend fun load(): LayerLoad
}
