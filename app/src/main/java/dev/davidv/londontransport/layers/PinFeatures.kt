package dev.davidv.londontransport.layers

import dev.davidv.londontransport.domain.AvailabilityLevel
import dev.davidv.londontransport.domain.DetailRow
import dev.davidv.londontransport.domain.FeatureId
import dev.davidv.londontransport.domain.FeatureKey
import dev.davidv.londontransport.domain.LayerId
import dev.davidv.londontransport.domain.MapFeature
import dev.davidv.londontransport.domain.MarkerStyle
import dev.davidv.londontransport.domain.Pin
import dev.davidv.londontransport.domain.PinId

val PIN_LAYER = LayerId("pins")

fun pinFeature(pin: Pin): MapFeature =
  MapFeature(
    key = FeatureKey(PIN_LAYER, FeatureId(pin.id.value)),
    position = pin.position,
    title = pin.name,
    level = AvailabilityLevel.UNAVAILABLE,
    badge = pin.name,
    summary = coordinates(pin),
    details =
      listOf(
        DetailRow("Latitude", "%.5f".format(pin.position.latitude)),
        DetailRow("Longitude", "%.5f".format(pin.position.longitude)),
      ),
    marker = MarkerStyle.Pin,
  )

fun pinIdOf(key: FeatureKey): PinId {
  check(key.layer == PIN_LAYER) { "not a pin: $key" }
  return PinId(key.feature.value)
}

private fun coordinates(pin: Pin): String =
  "%.5f, %.5f".format(pin.position.latitude, pin.position.longitude)
