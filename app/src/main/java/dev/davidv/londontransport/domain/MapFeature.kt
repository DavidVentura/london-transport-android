package dev.davidv.londontransport.domain

@JvmInline
value class LayerId(val value: String)

@JvmInline
value class FeatureId(val value: String)

data class FeatureKey(val layer: LayerId, val feature: FeatureId) {
  fun encode(): String = "${layer.value}$SEPARATOR${feature.value}"

  companion object {
    private const val SEPARATOR = '/'

    fun decode(encoded: String): FeatureKey {
      val separator = encoded.indexOf(SEPARATOR)
      check(separator > 0) { "not a feature key: $encoded" }
      return FeatureKey(
        layer = LayerId(encoded.substring(0, separator)),
        feature = FeatureId(encoded.substring(separator + 1)),
      )
    }
  }
}

/** How well stocked a feature is, on a scale the map palette and the list share. */
enum class AvailabilityLevel {
  UNKNOWN,
  NONE,
  LOW,
  MODERATE,
  PLENTY,
  UNAVAILABLE,
}

data class DetailRow(val label: String, val value: String)

/** How a feature is drawn: live data as a dot, a hand-placed marker as a pin, a bus stop as a
 * small ring in its line's colour. */
sealed interface MarkerStyle {
  data object Dot : MarkerStyle

  data object Pin : MarkerStyle

  data class Stop(val line: LineId, val kind: TransportKind) : MarkerStyle
}

/**
 * The only shape the map and the favourites list understand. Every layer flattens its own domain
 * objects into these, so neither screen knows what a docking station is.
 */
data class MapFeature(
  val key: FeatureKey,
  val position: LatLon,
  val title: String,
  val level: AvailabilityLevel,
  val badge: String,
  val summary: String,
  val details: List<DetailRow>,
  val marker: MarkerStyle = MarkerStyle.Dot,
)
