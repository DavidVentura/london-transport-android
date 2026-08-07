package dev.davidv.londoncycles.layers

import dev.davidv.londoncycles.domain.LayerId
import dev.davidv.londoncycles.domain.MapFeature

sealed interface LayerLoad {
  data class Loaded(val features: List<MapFeature>) : LayerLoad

  /** What was kept on disk, plus why it could not be refreshed. */
  data class Offline(val features: List<MapFeature>, val message: String) : LayerLoad

  data class Failed(val message: String) : LayerLoad
}

/**
 * A source of points to draw. Everything a layer knows about its own domain — endpoints, wire
 * formats, how a status reads in English — stops here; callers only ever see [MapFeature].
 */
interface MapLayer {
  val id: LayerId
  val title: String

  /** Whatever can be shown before the network answers. Empty when nothing is on disk. */
  suspend fun cached(): List<MapFeature>

  suspend fun load(): LayerLoad
}
