package dev.davidv.londoncycles.layers

import dev.davidv.londoncycles.domain.AvailabilityLevel
import dev.davidv.londoncycles.domain.DetailRow
import dev.davidv.londoncycles.domain.FeatureId
import dev.davidv.londoncycles.domain.FeatureKey
import dev.davidv.londoncycles.domain.LayerId
import dev.davidv.londoncycles.domain.MapFeature
import dev.davidv.londoncycles.domain.MarkerStyle
import dev.davidv.londoncycles.domain.RouteDirection
import dev.davidv.londoncycles.domain.RoutePath
import dev.davidv.londoncycles.domain.RouteStop
import dev.davidv.londoncycles.domain.TransportKind

val BUS_STOP_LAYER = LayerId("bus-stops")

/**
 * One stop of one saved route. The same kerb serves several lines, so the line is part of the key —
 * two saved routes through the same stop are two features, each answering for its own line.
 */
fun busStopFeature(
  stop: RouteStop,
  route: RoutePath,
  lineName: String,
  kind: TransportKind,
): MapFeature =
  MapFeature(
    key = FeatureKey(BUS_STOP_LAYER, FeatureId("${route.line.value}:${stop.id.value}")),
    position = stop.position,
    title = stop.name,
    level = AvailabilityLevel.UNKNOWN,
    badge = stop.letter.orEmpty(),
    summary = "$lineName towards ${route.towards}",
    details =
      listOfNotNull(
        stop.letter?.let { DetailRow("Stop", it) },
        DetailRow(if (kind == TransportKind.TUBE) "Line" else "Route", lineName),
        DetailRow("Direction", direction(route.direction)),
        DetailRow("Towards", route.towards),
      ),
    marker = MarkerStyle.Stop(route.line, kind),
  )

private fun direction(direction: RouteDirection): String =
  when (direction) {
    RouteDirection.INBOUND -> "Inbound"
    RouteDirection.OUTBOUND -> "Outbound"
  }
