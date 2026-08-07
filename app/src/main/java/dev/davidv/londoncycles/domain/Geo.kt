package dev.davidv.londoncycles.domain

data class LatLon(val latitude: Double, val longitude: Double)

data class MapCamera(val center: LatLon, val zoom: Double)

data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * The box the given points sit in, grown by [padding] of its own span on every side. Null when
 * there is nothing to frame.
 */
fun boundsAround(points: List<LatLon>, padding: Double): GeoBounds? {
  if (points.isEmpty()) return null

  val south = points.minOf { it.latitude }
  val north = points.maxOf { it.latitude }
  val west = points.minOf { it.longitude }
  val east = points.maxOf { it.longitude }
  val latitudePadding = (north - south) * padding
  val longitudePadding = (east - west) * padding

  return GeoBounds(
    south = south - latitudePadding,
    west = west - longitudePadding,
    north = north + latitudePadding,
    east = east + longitudePadding,
  )
}
